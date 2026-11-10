package net.kollnig.missioncontrol.tailscale;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tailscalebridge.Client;
import tailscalebridge.Tailscalebridge;

/** One service-owned engine, with no timers and no second reader of the VPN TUN. */
public final class TailscaleManager {
    private static final Object LIFE = new Object();
    private static final Object OUTPUT = new Object();
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static volatile Client client;
    private static volatile TailscaleSnapshot snapshot = TailscaleSnapshot.stopped();
    private static volatile String loginUrl = "";
    private static volatile boolean bridgeAttached;
    private static ConnectivityManager connectivity;
    private static TailscaleNetworkCallback networkCallback;
    private static ExecutorService networkExecutor;
    private static FileOutputStream output;
    private static ParcelFileDescriptor inputDescriptor;
    private static Thread inputThread, replyThread, eventThread;

    private TailscaleManager() {}

    public static TailscaleSnapshot snapshot() { return snapshot; }
    public static boolean isBridgeAttached() { return bridgeAttached; }
    public static String loginUrl() { return loginUrl; }
    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    private static void publish(TailscaleSnapshot next) {
        snapshot = next;
        notifyListeners();
    }

    private static void notifyListeners() {
        Handler main = new Handler(Looper.getMainLooper());
        for (Runnable listener : listeners) main.post(listener);
    }

    private static void failure(String reason) {
        TailscaleSnapshot old = snapshot;
        publish(new TailscaleSnapshot("Error", old.ipv4, old.ipv6, old.dnsSuffix, old.magicDNS, reason));
    }

    /** Called on the VPN command thread. Engine survives native-only/TUN reloads. */
    public static void start(VpnService service, Runnable reconfigure) {
        synchronized (LIFE) {
            if (client != null) return;
            try {
                go.Seq.setContext(service.getApplicationContext());
                TailscalePlatform platform = new TailscalePlatform(service);
                Client created = Tailscalebridge.new_(stateDirectory(service).getAbsolutePath(),
                        "trackercontrol-android", platform);
                client = created;
                readStatus(created, reconfigure);
                replyThread = new Thread(() -> receiveReplies(created), "Tailscale replies");
                replyThread.start();
                eventThread = new Thread(() -> {
                    try {
                        while (client == created) {
                            created.nextEvent();
                            if (client == created) readStatus(created, reconfigure);
                        }
                    } catch (Exception ignored) {
                        if (client == created) failure("Tailscale event stream stopped");
                    }
                }, "Tailscale state");
                eventThread.start();
                connectivity = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
                networkExecutor = Executors.newSingleThreadExecutor(task -> new Thread(task, "Tailscale network"));
                networkCallback = new TailscaleNetworkCallback(networkExecutor, () -> {
                    if (client != created) return;
                    try { created.networkChanged(platform.defaultInterface()); }
                    catch (Exception ignored) { }
                });
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
            } catch (Exception | LinkageError ex) {
                stop();
                failure("Tailscale could not start; disable and enable it to retry");
            }
        }
    }

    private static void readStatus(Client source, Runnable reconfigure) throws Exception {
        String json = source.statusJSON();
        TailscaleSnapshot next = TailscaleSnapshot.parse(json);
        boolean addressesChanged = !next.hasSameAddresses(snapshot);
        loginUrl = new JSONObject(json).optString("authURL", "");
        publish(next);
        if (addressesChanged) reconfigure.run();
    }

    public static void login() {
        Client current = client;
        if (current == null) { failure("Start protection with Tailscale enabled first"); return; }
        try { current.login(); }
        catch (Exception ex) { failure("Tailscale login could not start"); }
    }

    public static void reportUnavailable() {
        failure("Tailscale packet bridge unavailable; disable and enable it to retry");
    }

    /** Attach only a reader of the C-approved socketpair, plus a TUN write duplicate. */
    public static void attach(ParcelFileDescriptor vpn, int approvedPacketsFd) throws IOException {
        detach();
        ParcelFileDescriptor packets = ParcelFileDescriptor.adoptFd(approvedPacketsFd);
        try {
            ParcelFileDescriptor writer = ParcelFileDescriptor.dup(vpn.getFileDescriptor());
            // Native code sets O_NONBLOCK before this shared file description is duplicated.
            synchronized (OUTPUT) { output = new ParcelFileDescriptor.AutoCloseOutputStream(writer); }
        } catch (IOException ex) {
            packets.close();
            throw ex;
        }
        inputDescriptor = packets;
        Client target = client;
        inputThread = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(packets)) {
                byte[] buffer = new byte[65535];
                int length;
                while ((length = input.read(buffer)) > 0) {
                    if (target != null && target == client) {
                        // Go copies the prefix it queues; no per-packet array here.
                        try { target.sendPacketPrefix(buffer, length); }
                        catch (Exception ignored) { /* Not ready, forbidden, queue full: drop. */ }
                    }
                }
            } catch (IOException ignored) { }
            finally {
                // detach() joins this reader before a replacement can attach.
                bridgeAttached = false;
                notifyListeners();
            }
        }, "Tailscale approved packets");
        bridgeAttached = true;
        inputThread.start();
        notifyListeners();
    }

    private static void receiveReplies(Client source) {
        try {
            while (client == source) {
                byte[] packet = source.readPacket();
                synchronized (OUTPUT) {
                    if (client == source && output != null) {
                        try { output.write(packet); }
                        catch (IOException ignored) { /* TUN full/replacing: drop. */ }
                    }
                }
            }
        } catch (Exception ignored) {
            if (client == source) failure("Tailscale packet stream stopped");
        }
    }

    /** C must shutdown its socketpair writer before detaching, which wakes input.read. */
    public static void detach() {
        bridgeAttached = false;
        notifyListeners();
        synchronized (OUTPUT) {
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            output = null;
        }
        join(inputThread);
        inputThread = null;
        if (inputDescriptor != null) try { inputDescriptor.close(); } catch (IOException ignored) { }
        inputDescriptor = null;
    }

    public static void stop() {
        synchronized (LIFE) {
            Client previous = client;
            client = null;
            if (networkCallback != null) networkCallback.close();
            if (connectivity != null && networkCallback != null)
                try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
            connectivity = null;
            networkCallback = null;
            ExecutorService previousNetworkExecutor = networkExecutor;
            networkExecutor = null;
            if (previousNetworkExecutor != null) previousNetworkExecutor.shutdownNow();
            if (previous != null) try { previous.close(); } catch (Exception ignored) { }
            awaitNetworkStop(previousNetworkExecutor);
            join(replyThread);
            join(eventThread);
            replyThread = null;
            eventThread = null;
            loginUrl = "";
            publish(TailscaleSnapshot.stopped());
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitNetworkStop(ExecutorService executor) {
        if (executor == null) return;
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.DAYS); }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static File stateDirectory(Context context) {
        return new File(context.getNoBackupFilesDir(), "tailscale");
    }

    public static void logout(Context context) throws IOException {
        synchronized (LIFE) {
            stop();
            File root = stateDirectory(context);
            deleteState(root, root.getCanonicalPath());
        }
    }

    private static void deleteState(File file, String root) throws IOException {
        String path = file.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator))
            throw new IOException("Unexpected Tailscale state path");
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteState(child, root);
        if (file.exists() && !file.delete()) throw new IOException("Could not remove local Tailscale state");
    }

    /** null = ordinary DNS. Captured tailnet queries never fall back to public DNS. */
    public static byte[] resolveDNS(byte[] query) {
        if (!Tailscalebridge.isTailnetDNS(query, snapshot.dnsSuffix)) return null;
        Client current = client;
        if (current != null) {
            try {
                byte[] response = current.resolveDNS(query);
                if (response != null) return response;
            } catch (Exception ignored) { }
        }
        if (query.length < 12) return new byte[0];
        byte[] failure = query.clone();
        failure[2] = (byte) ((failure[2] | 0x80) & ~0x02);
        failure[3] = (byte) ((failure[3] & 0xf0) | 2); // SERVFAIL, never forward.
        // Preserve section counts along with their bytes (notably EDNS OPT).
        return failure;
    }
}
