# Examples

One folder per TV model, each with a `debloat.txt` listing the packages that are safe to disable
on that firmware, grouped and commented. These are starting points: comment out anything you use.

| Folder | TV | OS | Notes |
|---|---|---|---|
| `xiaomi-mi-tv-4a/` | Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 | PatchWall, Mi analytics, SmartShare, MiLink |
| `acer-v-pro-32-qled/` | Acer V Pro 32" QLED (AR32QDXGU2841AT) | Android 14 (Google TV) | Realtek RTD, DirectBoot aware, safe non-destructive HomeKeyService |
| `tcl-smart-tv-pro/` | TCL Smart TV Pro | Android 14 (Google TV) | MediaTek MT9618, 4 HDMI TIF passthroughs, TCL Channel debloat |

To add your TV, see [CONTRIBUTING.md](../CONTRIBUTING.md). The quickest way to get a list is to
run the prompt in [PROMPT.md](../PROMPT.md) and copy the packages your assistant disabled.
