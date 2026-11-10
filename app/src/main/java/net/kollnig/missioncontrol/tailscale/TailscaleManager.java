package net.kollnig.missioncontrol.tailscale;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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
    private static final Object PENDING_STOPS = new Object();
    private static final String TAG = "TrackerControl.Tailscale";
    private static int pendingStops;
    private static final int PACKET_BUFFER = 2048;
    /** How long start() waits for an engine that is still shutting down. Changed only by tests. */
    static long stopWaitMillis = 30_000;
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
        if (!awaitPendingStops()) {
            // Never start a second engine beside one that is still closing:
            // its socket protection hook is process-global.
            failure("Tailscale is still stopping; try again shortly");
            return;
        }
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
                // Native code only hands over packets of at most the overlay
                // MTU (1280). A buffer of that order keeps the cost at the
                // Java/Go boundary small even where the runtime copies the
                // array for the call instead of pinning it; a longer packet
                // would arrive truncated and be rejected by Go as malformed.
                byte[] buffer = new byte[PACKET_BUFFER];
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

    /**
     * stop() for the main thread. Shutting the engine down waits on Go and on
     * its threads without a time limit, which must never block the UI thread
     * (Service.onDestroy). The request is counted before this returns, and
     * start() waits for it: a later start can never be undone by this stop.
     */
    public static void stopAsync() {
        synchronized (PENDING_STOPS) { pendingStops++; }
        try {
            new Thread(() -> {
                try {
                    stop();
                } catch (RuntimeException ex) {
                    Log.e(TAG, "Tailscale stop failed", ex);
                } finally {
                    stopFinished();
                }
            }, "Tailscale stop").start();
        } catch (RuntimeException | Error ex) {
            // No thread, so nobody else would ever take the request back.
            stopFinished();
            throw ex;
        }
    }

    /**
     * Teardown for Service.onDestroy, which runs on the main thread.
     * Only the engine shutdown is asynchronous. shutdownNativeWriter and
     * detach() run here and must return promptly: shutdownNativeWriter has
     * to shut the native end of the packet bridge down, which wakes the
     * packet reader that detach() waits for.
     */
    public static void destroy(Runnable shutdownNativeWriter) {
        shutdownNativeWriter.run();
        detach();
        stopAsync();
    }

    private static void stopFinished() {
        synchronized (PENDING_STOPS) {
            pendingStops--;
            PENDING_STOPS.notifyAll();
        }
    }

    /** False if an earlier stop is still running after stopWaitMillis. */
    private static boolean awaitPendingStops() {
        boolean interrupted = false;
        boolean stopped = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopWaitMillis);
        synchronized (PENDING_STOPS) {
            while (pendingStops > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { stopped = false; break; }
                try { TimeUnit.NANOSECONDS.timedWait(PENDING_STOPS, remaining); }
                catch (InterruptedException ex) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return stopped;
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

    /**
     * Erases the local identity. While the engine runs it first asks the
     * control server to expire the node key, so that a copy of the erased
     * keys surviving somewhere would be useless. That request is bounded and
     * best effort: offline, on a timeout or on a server error the key may
     * stay valid, until it expires or for good where the tailnet has key
     * expiry switched off. The local erase is attempted
     * either way and reports its own failure.
     * Never call this on the main thread: it waits for the control server
     * (up to five seconds) and for the engine shutdown. The VPN command
     * thread is the only caller.
     */
    public static void logout(Context context) throws IOException {
        synchronized (LIFE) {
            Client current = client;
            if (current != null)
                try { current.logout(); }
                catch (Exception ex) {
                    // The local erase still happens. No detail: it may name the account.
                    Log.w(TAG, "Tailscale server-side logout failed; erasing local state");
                }
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
        String suffix = snapshot.dnsSuffix;
        // Most queries are plainly ordinary; only the rest are worth a call into Go.
        if (TailnetNames.isCertainlyOrdinary(query, suffix)) return null;
        if (!Tailscalebridge.isTailnetDNS(query, suffix)) return null;
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
