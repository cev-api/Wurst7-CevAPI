"""Wurst7-CevAPI custom build profile editor.

Run with: python scripts/custom_build_gui.py
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import tempfile
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

ROOT = Path(__file__).resolve().parents[1]
PROFILE_DIR = ROOT / "custom-build"
PROFILE_FILE = PROFILE_DIR / "profile.json"
DEFAULTS_DIR = PROFILE_DIR / "defaults"
ASSETS_DIR = PROFILE_DIR / "assets"
REGISTRIES = {
    "hacks": ROOT / "src/main/java/net/wurstclient/hack/HackList.java",
    "commands": ROOT / "src/main/java/net/wurstclient/command/CmdList.java",
    "other_features": ROOT / "src/main/java/net/wurstclient/other_feature/OtfList.java",
}
SUFFIXES = {"hacks": "Hack", "commands": "Cmd", "other_features": "Otf"}
WURST_OPTION_SOURCES = (
    "other_features/WurstOptionsOtf.java",
    "other_features/DisableOtf.java",
    "other_features/CommandPrefixOtf.java",
    "other_features/NoTelemetryOtf.java",
    "other_features/NoChatReportsOtf.java",
    "other_features/ForceAllowChatsOtf.java",
    "other_features/VanillaSpoofOtf.java",
    "other_features/TranslationsOtf.java",
    "other_features/WurstCapesOtf.java",
    "other_features/ConnectionLogOverlayOtf.java",
    "hacks/NavigatorHack.java",
)


def discover(kind: str) -> list[tuple[str, str]]:
    """Return (stable profile ID, display name) without loading Minecraft."""
    if kind == "wurst_options":
        source_root = ROOT / "src/main/java/net/wurstclient"
        pattern = re.compile(
            r'new\s+(?:\w*Setting(?:<[^>]*>|<>)?|SettingGroup)\s*\(\s*"([^"]+)"',
            re.MULTILINE,
        )
        names: set[str] = set()
        for relative in WURST_OPTION_SOURCES:
            names.update(pattern.findall((source_root / relative).read_text(encoding="utf-8")))
        # This setting belongs only to NoChatReports and is not linked here.
        names.discard("Unsafe Chat Toast")
        return sorted(((name, name) for name in names), key=lambda item: item[1].lower())

    text = REGISTRIES[kind].read_text(encoding="utf-8")
    suffix = SUFFIXES[kind]
    pattern = re.compile(
        rf"public\s+final\s+([\w.]+)\s+(\w+{suffix})\s*=", re.MULTILINE
    )
    return sorted(((field, java_type.rsplit(".", 1)[-1])
                   for java_type, field in pattern.findall(text)),
                  key=lambda item: item[1].lower())


def defaults() -> dict:
    return {
        "enabled": False,
        "suffix": "Modified",
        "hacks": [item[0] for item in discover("hacks")],
        "commands": [item[0] for item in discover("commands")],
        "other_features": [item[0] for item in discover("other_features")],
        "wurst_options": [item[0] for item in discover("wurst_options")],
    }


def load_profile() -> dict:
    profile = defaults()
    if PROFILE_FILE.exists():
        try:
            loaded = json.loads(PROFILE_FILE.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                # Old branding fields are deliberately ignored.
                for key in profile:
                    if key in loaded:
                        profile[key] = loaded[key]
        except (OSError, json.JSONDecodeError):
            pass
    return profile


class FeaturePage(ttk.Frame):
    def __init__(self, master: tk.Misc, kind: str, included: list[str]):
        super().__init__(master)
        self.items = discover(kind)
        self.item_by_id = dict(self.items)
        self.included = set(included) & set(self.item_by_id)
        self.query = tk.StringVar()

        bar = ttk.Frame(self)
        bar.pack(fill="x", padx=8, pady=8)
        ttk.Label(bar, text="Search:").pack(side="left")
        ttk.Entry(bar, textvariable=self.query).pack(
            side="left", fill="x", expand=True, padx=6)
        self.query.trace_add("write", lambda *_: self.refresh())
        ttk.Button(bar, text="Include all", command=self.include_all).pack(side="left", padx=2)
        ttk.Button(bar, text="Exclude all", command=self.exclude_all).pack(side="left", padx=2)

        body = ttk.Frame(self)
        body.pack(fill="both", expand=True, padx=8, pady=(0, 8))
        body.columnconfigure(0, weight=1)
        body.columnconfigure(2, weight=1)
        body.rowconfigure(1, weight=1)
        ttk.Label(body, text="Exclude").grid(row=0, column=0)
        ttk.Label(body, text="Include").grid(row=0, column=2)

        self.excluded_list = tk.Listbox(body, selectmode="extended", exportselection=False)
        self.included_list = tk.Listbox(body, selectmode="extended", exportselection=False)
        self.excluded_list.grid(row=1, column=0, sticky="nsew")
        self.included_list.grid(row=1, column=2, sticky="nsew")
        self.excluded_list.bind("<Double-Button-1>", lambda _e: self.include_selected())
        self.included_list.bind("<Double-Button-1>", lambda _e: self.exclude_selected())

        buttons = ttk.Frame(body)
        buttons.grid(row=1, column=1, padx=8)
        ttk.Button(buttons, text=">", width=4,
                   command=self.include_selected).pack(pady=4)
        ttk.Button(buttons, text="<", width=4,
                   command=self.exclude_selected).pack(pady=4)
        self.excluded_ids: list[str] = []
        self.included_ids: list[str] = []
        self.refresh()

    def _matching(self, included: bool) -> list[str]:
        query = self.query.get().strip().lower()
        return [item_id for item_id, display in self.items
                if (item_id in self.included) == included
                and (query in item_id.lower() or query in display.lower())]

    def refresh(self) -> None:
        self.excluded_ids = self._matching(False)
        self.included_ids = self._matching(True)
        self.excluded_list.delete(0, "end")
        self.included_list.delete(0, "end")
        for item_id in self.excluded_ids:
            self.excluded_list.insert("end", self._label(item_id))
        for item_id in self.included_ids:
            self.included_list.insert("end", self._label(item_id))

    def _label(self, item_id: str) -> str:
        display = self.item_by_id[item_id]
        return display if display == item_id else f"{display}   [{item_id}]"

    def include_selected(self) -> None:
        self.included.update(self.excluded_ids[i] for i in self.excluded_list.curselection())
        self.refresh()

    def exclude_selected(self) -> None:
        self.included.difference_update(
            self.included_ids[i] for i in self.included_list.curselection())
        self.refresh()

    def include_all(self) -> None:
        self.included.update(item_id for item_id, _display in self.items)
        self.refresh()

    def exclude_all(self) -> None:
        self.included.clear()
        self.refresh()

    def value(self) -> list[str]:
        return sorted(self.included)


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Wurst7-CevAPI Custom Build")
        self.geometry("1050x700")
        self.minsize(800, 540)
        self.profile = load_profile()
        self.suffix = tk.StringVar(value=self.profile.get("suffix", "Modified"))
        self.enabled = tk.BooleanVar(value=bool(self.profile.get("enabled", False)))
        self.status = tk.StringVar(value=f"Profile: {PROFILE_FILE}")

        notebook = ttk.Notebook(self)
        notebook.pack(fill="both", expand=True, padx=8, pady=8)
        general = ttk.Frame(notebook)
        notebook.add(general, text="Name & defaults")
        self._build_general(general)
        self.pages = {}
        for kind, title in (("hacks", "Hacks"), ("commands", "Commands"),
                            ("other_features", "Other features"),
                            ("wurst_options", "Wurst Options settings")):
            page = FeaturePage(notebook, kind, self.profile.get(kind, []))
            notebook.add(page, text=title)
            self.pages[kind] = page

        footer = ttk.Frame(self)
        footer.pack(fill="x", padx=8, pady=(0, 8))
        ttk.Label(footer, textvariable=self.status).pack(side="left", fill="x", expand=True)
        ttk.Button(footer, text="Save profile", command=self.save).pack(side="right", padx=3)
        ttk.Button(footer, text="Save & build", command=self.build).pack(side="right", padx=3)

    def _build_general(self, parent: ttk.Frame) -> None:
        parent.columnconfigure(1, weight=1)
        ttk.Checkbutton(parent, text="Enable custom-build profile",
                        variable=self.enabled).grid(row=0, column=0, columnspan=3,
                                                    sticky="w", padx=12, pady=12)
        ttk.Label(parent, text="Custom suffix:").grid(row=1, column=0, sticky="e", padx=8, pady=5)
        ttk.Entry(parent, textvariable=self.suffix).grid(
            row=1, column=1, sticky="ew", padx=8, pady=5)
        ttk.Label(parent, text="Result: Wurst7-CevAPI-<suffix>").grid(
            row=1, column=2, sticky="w", padx=8)

        ttk.Separator(parent).grid(row=2, column=0, columnspan=3, sticky="ew", padx=8, pady=12)
        ttk.Label(parent, text="Optional build assets and first-run defaults").grid(
            row=3, column=0, columnspan=3, sticky="w", padx=12)
        buttons = (
            ("Mod icon PNG", "icon.png", "PNG files", "*.png"),
            ("Menu logo PNG", "menu_logo.png", "PNG files", "*.png"),
            ("Title Shadertoy shader", "title_background.shadertoy.glsl", "GLSL files", "*.glsl"),
            ("settings.json", "settings.json", "JSON files", "*.json"),
            ("enabled-hacks.json", "enabled-hacks.json", "JSON files", "*.json"),
            ("keybinds.json", "keybinds.json", "JSON files", "*.json"),
        )
        for row, (label, dest, desc, glob) in enumerate(buttons, 4):
            ttk.Button(parent, text=f"Import {label}",
                       command=lambda d=dest, de=desc, g=glob: self.import_file(d, de, g)).grid(
                           row=row, column=0, columnspan=2, sticky="ew", padx=12, pady=4)
            target = (ASSETS_DIR if dest.endswith((".png", ".glsl")) else DEFAULTS_DIR) / dest
            ttk.Label(parent, text="installed" if target.exists() else "not set").grid(
                row=row, column=2, sticky="w", padx=8)

    def import_file(self, destination: str, description: str, glob: str) -> None:
        source = filedialog.askopenfilename(filetypes=[(description, glob), ("All files", "*.*")])
        if not source:
            return
        target_dir = ASSETS_DIR if destination.endswith((".png", ".glsl")) else DEFAULTS_DIR
        target_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target_dir / destination)
        self.status.set(f"Imported {destination}. Reopen the GUI to refresh indicators.")

    def save(self) -> bool:
        suffix = self.suffix.get().strip()
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,31}", suffix):
            messagebox.showerror(
                "Invalid suffix",
                "Use 1-32 letters, digits, dots, underscores or hyphens.")
            return False
        profile = {"enabled": self.enabled.get(), "suffix": suffix}
        profile.update({kind: page.value() for kind, page in self.pages.items()})
        PROFILE_DIR.mkdir(parents=True, exist_ok=True)
        PROFILE_FILE.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        self.status.set("Profile saved. Gradle will consume it on the next build.")
        return True

    def build(self) -> None:
        if not self.save():
            return
        self.status.set("Building…")

        def worker() -> None:
            command = [str(ROOT / "gradlew.bat"), "-p", str(ROOT),
                       "build", "--no-daemon"]
            result = subprocess.run(command, cwd=tempfile.gettempdir(),
                                    text=True, capture_output=True)
            log = PROFILE_DIR / "last-build.log"
            log.write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
            message = ("Build complete." if result.returncode == 0
                       else f"Build failed (exit {result.returncode}).")
            self.after(0, lambda: self.status.set(f"{message} Log: {log}"))
            self.after(0, lambda: messagebox.showinfo("Custom build", f"{message}\n\n{log}"))

        threading.Thread(target=worker, daemon=True).start()


if __name__ == "__main__":
    App().mainloop()