# Graph Report - tv-vibehack  (2026-10-20)

## Corpus Check
- 25 files · ~15,943 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 224 nodes · 444 edges · 18 communities (15 shown, 3 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 8 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7ebe7a2b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MainActivity.java
- MainActivity
- AppAdapter
- Acer V Pro Series 32-inch QLED Smart Google TV — Reverse Engineering & Architecture Deep Dive
- GlassView
- tv-vibehack 📺✨
- VideoPlayerActivity
- android.os.Handler
- android.content.Intent
- tv.py
- build-and-install.sh
- gradlew
- disable.sh
- restore.sh
- safe_switch.sh

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 31 edges
2. `AppAdapter` - 30 edges
3. `VideoPlayerActivity` - 21 edges
4. `PenDriveActivity` - 12 edges
5. `Entry` - 10 edges
6. `GlassView` - 9 edges
7. `tv-vibehack 📺✨` - 9 edges
8. `Holder` - 8 edges
9. `DockDecoration` - 7 edges
10. `HomeKeyService` - 7 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `AppAdapter`  [EXTRACTED]
  launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java → launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java
- `MainActivity` --implements--> `TileListener`  [EXTRACTED]
  launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java → launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java
- `MainActivity` --references--> `GlassView`  [EXTRACTED]
  launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java → launcher/app/src/main/java/com/gathin/tvhome/GlassView.java

## Import Cycles
- None detected.

## Communities (18 total, 3 thin omitted)

### Community 0 - "MainActivity.java"
Cohesion: 0.14
Nodes (15): android.app.Activity, android.content.SharedPreferences, android.os.Bundle, android.view.KeyEvent, android.view.View, android.view.ViewGroup, android.widget.FrameLayout, android.widget.ImageView (+7 more)

### Community 1 - "MainActivity"
Cohesion: 0.11
Nodes (10): android.graphics.drawable.GradientDrawable, android.widget.LinearLayout, android.widget.PopupWindow, androidx.dynamicanimation.animation.SpringAnimation, GradientDrawable, Intent, Override, MainActivity (+2 more)

### Community 2 - "AppAdapter"
Cohesion: 0.17
Nodes (9): Adapter, android.content.ComponentName, android.content.Context, android.graphics.drawable.Drawable, ComponentName, AppAdapter, Entry, Intent (+1 more)

### Community 3 - "Acer V Pro Series 32-inch QLED Smart Google TV — Reverse Engineering & Architecture Deep Dive"
Cohesion: 0.08
Nodes (19): 1. Add your TV model, 2. Improve the launcher, 3. Improve the prompt and tools, Contributing, Pull requests, 1. Clean Architecture & Verified Pipeline, 2. Reverse-Engineered Hardware & Architecture, 3. The Android 14 Bootloop Crisis & Root Cause Analysis (+11 more)

### Community 4 - "GlassView"
Cohesion: 0.18
Nodes (12): android.graphics.Canvas, android.graphics.Paint, android.graphics.Rect, android.graphics.RectF, android.util.AttributeSet, androidx.recyclerview.widget.RecyclerView, ItemDecoration, DockDecoration (+4 more)

### Community 5 - "tv-vibehack 📺✨"
Cohesion: 0.11
Nodes (19): 1. Enable ADB on your Android TV, 1. 🛡️ The 100% Fail-Safe Architecture (Zero Bootloops), 2. Connect via ADB, 2. 🔀 Hardware Passthrough (Direct HDMI Switching), 3. Safe Debloat, 3. 🎮 Sponsored Remote Key Remapping (No Root), 4. Build & Install the Launcher, 4. 🎞️ Offline TV Media Vault & Legacy Transcoding (+11 more)

### Community 6 - "VideoPlayerActivity"
Cohesion: 0.24
Nodes (5): android.widget.ProgressBar, android.widget.SeekBar, android.widget.VideoView, Override, VideoPlayerActivity

### Community 7 - "android.os.Handler"
Cohesion: 0.22
Nodes (7): android.accessibilityservice.AccessibilityService, android.os.Handler, android.view.accessibility.AccessibilityEvent, HdmiHelper, Intent, HomeKeyService, Override

### Community 8 - "android.content.Intent"
Cohesion: 0.27
Nodes (5): android.content.BroadcastReceiver, android.content.Intent, BootReceiver, Intent, Override

### Community 9 - "tv.py"
Cohesion: 0.47
Nodes (3): connect(), get_signer(), main()

### Community 10 - "build-and-install.sh"
Cohesion: 0.40
Nodes (4): ANDROID_HOME, JAVA_HOME, PATH, build-and-install.sh script

### Community 11 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **36 isolated node(s):** `build-and-install.sh script`, `JAVA_HOME`, `ANDROID_HOME`, `PATH`, `disable.sh script` (+31 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `MainActivity.java`, `AppAdapter`, `GlassView`, `android.os.Handler`, `android.content.Intent`?**
  _High betweenness centrality (0.152) - this node is a cross-community bridge._
- **Why does `AppAdapter` connect `AppAdapter` to `MainActivity.java`, `MainActivity`, `GlassView`?**
  _High betweenness centrality (0.116) - this node is a cross-community bridge._
- **Why does `VideoPlayerActivity` connect `VideoPlayerActivity` to `MainActivity.java`, `android.os.Handler`?**
  _High betweenness centrality (0.073) - this node is a cross-community bridge._
- **What connects `build-and-install.sh script`, `JAVA_HOME`, `ANDROID_HOME` to the rest of the system?**
  _36 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity.java` be split into smaller, more focused modules?**
  _Cohesion score 0.14193548387096774 - nodes in this community are weakly interconnected._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.11397849462365592 - nodes in this community are weakly interconnected._
- **Should `Acer V Pro Series 32-inch QLED Smart Google TV — Reverse Engineering & Architecture Deep Dive` be split into smaller, more focused modules?**
  _Cohesion score 0.08333333333333333 - nodes in this community are weakly interconnected._