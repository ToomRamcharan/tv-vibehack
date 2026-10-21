# Examples

One folder per TV model, each with a `debloat.txt` listing the packages that are safe to disable
on that firmware, grouped and commented. These are starting points: comment out anything you use.

| Folder | TV | OS | Notes |
|---|---|---|---|
| `xiaomi-mi-tv-4a/` | Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 | PatchWall, Mi analytics, SmartShare, MiLink |
| `acer-v-pro-32-qled/` | Acer V Pro 32" QLED (AR32QDXGU2841AT) | Android 14 (Google TV) | Realtek RTD, DirectBoot aware, safe non-destructive HomeKeyService |
| `xiaomi-mi-box-3/` | Xiaomi Mi Box 3 (MIBOX3) | Android TV 9 | Stock launcher disabled to set Home; no HDMI inputs |
| `xiaomi-mi-tv-mssp2/` | Xiaomi Mi TV (MiTV-MSSP2) | Android TV 9 | MediaTek HDMI/AV inputs; weak-Wi-Fi install tips |

To add your TV, see [CONTRIBUTING.md](../CONTRIBUTING.md). The quickest way to get a list is to
run the prompt in [PROMPT.md](../PROMPT.md) and copy the packages your assistant disabled.
