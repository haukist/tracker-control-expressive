"""Exercise production Gradle input tracking without compiling an Android AAR.

Run with Python 3.10+, a JDK, and the repository's Gradle wrapper available.
Only the task action is replaced; resolution and input declarations are real.
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
WINDOWS = os.name == "nt"


class BuildInputTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tc-build-inputs-")
        self.root = Path(self.temporary.name).resolve()
        # Cleanup is confined to this newly allocated test directory.
        self.root.relative_to(Path(tempfile.gettempdir()).resolve())
        self.addCleanup(self.temporary.cleanup)
        for directory in ["app", "scripts", "tools", "go-root/pkg/tool", "tailscale-go"]:
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        self.tools = self.root / "tools"
        self.compiler = self.root / "go-root/pkg/tool/compile.bin"
        self.compiler.write_bytes(b"compiler-v1")
        (self.root / "go-root/VERSION").write_text("go1.27.3", encoding="utf-8")
        (self.root / "tailscale-go/bridge.go").write_text("package bridge\n", encoding="utf-8")
        shutil.copyfile(REPO / "scripts/build_tailscale_poc.py", self.root / "scripts/build_tailscale_poc.py")
        self.go = self.fake_tool("go", True)
        self.mobile = self.fake_tool("gomobile")
        self.bind = self.fake_tool("gobind")
        (self.root / "settings.gradle").write_text("rootProject.name='inputs-test'\ninclude ':app'\n", encoding="utf-8")
        apply_path = json.dumps((REPO / "app/gradle/tailscale.gradle").as_posix())
        (self.root / "app/build.gradle").write_text("""
configurations { implementation }
apply from: %s
tasks.named('tailscaleBuild') {
    setActions([])
    doLast {
        def count = rootProject.file('count.txt')
        count.text = Integer.toString(count.exists() ? Integer.parseInt(count.text) + 1 : 1)
        def aar = rootProject.file('tailscale-go/build/tailscale.aar')
        aar.parentFile.mkdirs()
        aar.text = 'fixture ' + count.text
    }
}
""" % apply_path, encoding="utf-8")
        self.env = os.environ.copy()
        self.env["PATH"] = str(self.tools) + os.pathsep + self.env.get("PATH", "")

    def fake_tool(self, name, go=False):
        path = self.tools / (name + (".cmd" if WINDOWS else ""))
        if go:
            implementation = self.tools / "fake_go.py"
            implementation.write_text("""import json
from pathlib import Path
root = Path(__file__).resolve().parent.parent / 'go-root'
print(json.dumps({'GOROOT': str(root), 'GOTOOLDIR': str(root/'pkg/tool'), 'GOVERSION': 'go1.27.3'}))
""", encoding="utf-8")
            if WINDOWS:
                path.write_text("@" + subprocess.list2cmdline([sys.executable, str(implementation)]) + " %*\n", encoding="utf-8")
            else:
                path.write_text("#!" + sys.executable + "\nimport runpy\nrunpy.run_path(" + repr(str(implementation)) + ")\n", encoding="utf-8")
        else:
            path.write_text("@rem fixture\n" if WINDOWS else "#!/bin/sh\nexit 0\n", encoding="utf-8")
        path.chmod(0o755)
        return path

    def run_build(self, *properties):
        wrapper = REPO / ("gradlew.bat" if WINDOWS else "gradlew")
        result = subprocess.run([str(wrapper), "--no-daemon", "--console=plain", "--project-dir", str(self.root),
                                 ":app:tailscaleBuild", "-PpythonBin=" + sys.executable, *properties],
                                cwd=REPO, env=self.env, capture_output=True, text=True, errors="replace", timeout=180)
        self.assertEqual(result.returncode, 0, result.stdout[-5000:] + result.stderr[-5000:])
        return int((self.root / "count.txt").read_text())

    def replace_tool(self, path):
        with path.open("a", encoding="utf-8") as output:
            output.write("\n@rem updated\n" if WINDOWS else "\n# updated\n")

    def test_path_tools_and_go_compiler_changes_rebuild(self):
        self.assertEqual(self.run_build(), 1)
        self.assertEqual(self.run_build(), 1, "unchanged inputs must reuse the AAR")
        for expected, tool in enumerate([self.go, self.mobile, self.bind], start=2):
            self.replace_tool(tool)
            self.assertEqual(self.run_build(), expected, "tool contents changed at the same PATH")
        self.compiler.write_bytes(b"compiler-v2")
        self.assertEqual(self.run_build(), 5, "Go compiler changed without changing the driver")

    def test_relative_go_path_is_resolved_from_repository_root(self):
        relative = "-PgoBin=" + self.go.relative_to(self.root).as_posix()
        self.assertEqual(self.run_build(relative), 1)
        self.assertEqual(self.run_build(relative), 1)
        self.replace_tool(self.go)
        self.assertEqual(self.run_build(relative), 2)


class BuildTagTest(unittest.TestCase):
    """The shipped AAR and the Go tests must compile the same feature set."""

    def test_printed_tags_are_the_ones_passed_to_gomobile(self):
        script = REPO / "scripts/build_tailscale_poc.py"
        printed = subprocess.run([sys.executable, str(script), "--print-tags"], check=True,
                                 capture_output=True, text=True).stdout.strip()
        tags = printed.split(",")
        # New() refuses to start an engine built with the peer API server.
        self.assertIn("ts_omit_peerapiserver", tags)
        # No uploader of logs or network flow logs may be compiled in.
        self.assertIn("ts_omit_logtail", tags)
        self.assertIn("ts_omit_netlog", tags)
        self.assertEqual(len(tags), len(set(tags)))
        for tag in tags:
            self.assertRegex(tag, r"^ts_omit_[a-z0-9_]+$")
        # The AAR build passes exactly the printed list, and nothing else as tags.
        spec = importlib.util.spec_from_file_location("build_tailscale_poc", script)
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        output = Path("out.aar")
        self.assertEqual(builder.bind_command("gomobile", output), [
            "gomobile", "bind", "-target=android", "-androidapi=23", f"-tags={printed}",
            "-trimpath", "-ldflags=-buildid= -s -w -extldflags=-Wl,-z,max-page-size=16384",
            "-o", str(output), "."])


if __name__ == "__main__":
    unittest.main(verbosity=2)
