# BirdLog — Pebble Time 2 birding companion

**Source release 1.0.0 — October 2026**

A two-part birding field notebook: a native **Pebble Time 2 (Emery) C watchapp** and a **native Android Kotlin companion**. The watch is the field user interface. Android owns persistent observations, GPS, eBird reference data, speech interpretation and exports. **STT, not TTS:** your watch takes spoken field notes and confirms them with vibration/visual feedback; it does **not** synthesize speech.

This is a substantial first implementation, **not a prebuilt or device-tested release**. The source exporter has been independently compiled and tested with the local Kotlin compiler, but compiling the actual watch binary and Android APK requires external SDKs not present in the creation environment. Initial SDK build fixes may still be necessary. `demo/interactive-prototype.html` is a *separate visual mockup* and does not connect to a real watch or GPS.

## Functions implemented

- Pebble 200×228 native checklist, 5 visible rows at a time; running count on the left and common/scientific names on the right; pageable catalog sent from Android.
- **Up/Down** scroll; **middle** opens Add +1/Edit total/Delete; **hold Up** for Pebble dictation; **hold Down** for blue-point sighting map with touch/Up/Down selection and time/species detail; **hold Middle** to save and exit after phone confirmation.
- Home menu: new outing / open saved outing / current checklist. Saved runs show timestamps and can be resumed.
- Native Android companion: live outing view, manual corrections, saved runs, phone STT, settings, eBird reference species download, tracked GPS location service with foreground notification, exports to CSV/KML/JSON.
- Watch dictation transcripts or phone STT are parsed locally first. Optional **Groq LLM** is called **only to resolve unrecognized species**, and is constrained to an exact code in the downloaded taxonomy. All records validate against the phone's catalog; no AI-authored taxon is accepted blindly.
- eBird nearby *recent* sightings (up to the previous 30 days within 25 km) used as a **season-and-location proxy**, ranked before the larger full eBird taxonomy. This **is not yet a rigorous multi-year seasonal frequency model**. The first-run offline starter taxonomy has ~50 common birds and is not automatically regionally accurate. The complete downloaded catalog can be displayed via Settings and is always accessible for voice lookup.
- Immutable sighting adjustment event history; final counts are derived, not overwritten. GPS fix samples and each sighting's own timestamp/near-observer phone location saved to SQLite. Events lacking immediate locations are spatially interpolated only when valid nearby GPS fixes exist; interpolation is labeled, never presented as a measured bird position.
- eBird **Record Format CSV**: 19 fields in the current format with no header row, correct date/time/effort fields, check for country/region codes, single-date checklist, valid protocol and actual track distance for Traveling. Data must still be reviewed and manually imported on eBird; there is **no direct eBird posting API**.
- KML `gx:Track` with timestamp for each GPS fix; each active observation has its own timestamped blue-dot placemark, count and interpolation flag; folder contains final species totals. JSON backup contains all raw changes, original timestamped GPS fixes and final totals.

## Minimum setup

### A. Android companion

1. Install **Android Studio** with Android SDK Platform 36 and JDK 17. Open the `android/` directory as a Gradle Android project. Let Studio install required plugins and dependencies from Maven.
2. Build and install the `app` debug configuration. The application ID is `org.birdlog.app`. CLI alternative if Gradle 8.13 is installed (Gradle wrapper is not included in this ZIP):
   ```bash
   cd android
   gradle --no-daemon :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Launch **BirdLog** on Android once. Approve precise/coarse location and notification permissions when requested. Configure Settings: **Country** (`US`), **State/province** (`MA`, `NH`, etc.), correct **protocol** (Traveling, Stationary, Incidental, Historical), number of observers and **complete?** flag. Set a meaningful **outing/site name**.
4. For a regional checklist, obtain an **eBird API token** and put it in BirdLog settings. Tap **Update local/seasonal eBird species** while the phone has a recent GPS fix and Internet. This downloads the eBird species taxonomy and locally reported birds. You can also use the offline starter catalog while disconnected.
5. Optional: add a **Groq API key** and model in settings (default `llama-3.3-70b-versatile`). Only parsed **text transcriptions** go to Groq when deterministic name matching fails. This can incur provider charges; the local parser requires no Groq credentials.

### B. Pebble Time 2

1. Install Pebble SDK **4.9.169 or newer** (needs `PBL_TOUCH` support) and its `pebble` command. Connect Pebble Time 2 to the Android Pebble/Core mobile app.
2. From the `watch/` directory:
   ```bash
   pebble build
   pebble install --phone 192.0.2.10     # substitute your phone's reachable address for a supported install connection
   ```
   Or sideload the built `watch/build/birdlog.pbw` / most recent `.pbw` via the Pebble app. The Pebble SDK chooses the actual PBW filename. The two halves share watchapp UUID `b18d10a6-30b2-4b59-9549-b1a973e18041`.
3. Start the Android companion *before* starting a GPS outing on the watch the first time. Android restricts location-foreground-service launch while the app is entirely in the background; if GPS does not start, foreground the phone app and tap **Start new outing** there.

### C. Recording and exporting

- Say **“three black-capped chickadees”** with **hold Up** and confirm Pebble dictation. Adds +3 at the current observer GPS position. Say **“set total to five blue jays”** to reset species aggregate to five (not add five). Repeated descriptions of the same flock can double-count; the user should review totals.
- Short-press middle to open **Add +1 / Edit total / Delete sightings** for selected species. A deletion resets the species total to zero via an auditable negative adjustment.
- Hold Down to inspect current **map**. Touch a blue dot (or use Up/Down then Select) for its species, count and observation time. The dot records where *the observer with the phone* was; it is **not a surveyed position of the bird**.
- Hold middle to save the current session to Android and exit the watchapp once the phone responds.
- On Android, export **eBird CSV**, **KML**, or **JSON** with the Save As chooser. For saved outings choose **Open another saved outing** and then its export action. Import the CSV manually at eBird's **Submit > Import Data > eBird Record Format**.

## Pebble navigation

| Watch control | Screen | Action |
| --- | --- | --- |
| Up / Down | Checklist | Scroll species; page at page boundaries |
| Middle short | Checklist | Context menu: Add +1 / Edit total / Delete |
| Up hold | Any | Dictate count and species (uses Pebble STT) |
| Down hold | Any | Map of geotagged sightings and track |
| Middle hold | Any | Save the outing to Android and close watchapp on confirmed save |
| Tap blue point | Map, touch enabled | Open species/count/time details |
| Up / Down then middle | Map | Choose and inspect sighting without touch |
| Back | Context, map, point | Return |

## Understanding stored location data

- Android foreground GPS records samples during a running outing (GPS roughly every 10s/8 m; network updates less often). Actual rate is controlled by Android/hardware/location permissions and may be irregular. Each sighting also stores its own approximate **phone position** if recent enough.
- For events with no immediate GPS position, the KML exporter may interpolate **between time-adjacent fixes** no more than two minutes apart with reasonable accuracy. The resulting coordinates are tagged `interpolated=true`; otherwise the observation is retained but no fabricated map point is emitted.
- The watch map holds the **48 most recent** geotagged sightings and a **24-fix downsample** of the route; the KML and SQLite database keep the full set.
- Stop/Save disables ongoing GPS background tracking. Android shows an explicit foreground notification while the route is tracked. BirdLog does not track before an outing begins.
- **GPS-based distance is an estimate**. Review obvious GPS jumps and effort metadata before import. Because eBird requires one site/date per checklist, split a multi-site route into separate outings. Very long outings or ones that cross midnight cannot be exported as a single eBird CSV by design.
- BirdLog does **not** auto-upload the KML with the eBird CSV. eBird's CSV import does not accept KML tracks as an integrated checklist attachment.

## Building with GitHub Actions (without Android Studio)

A workflow is included at `.github/workflows/android-debug.yml`. Push this folder as a GitHub repository and trigger **Actions > Android debug APK > Run workflow**. Download its build artifact to obtain a debug APK if the CI build succeeds. The Pebble watchapp still requires the Pebble SDK or a separate Pebble build environment.

## Local verification

The non-Android-dependent actual export engine is tested with a standalone Kotlin CLI test. With `kotlinc` installed:

```bash
./test-exporters.sh
```

The test checks eBird CSV's 19-column shape, correct species totals after edits, preservation of KML timestamps and GPS tracks, interpolation guards, and XML parse validity. See `examples/` for sample outputs from the real export engine.

## Known constraints in this release

- **No prebuilt `.apk` or `.pbw`** is provided: both platform SDKs were unavailable in the source generation environment. Actual hardware integration, connection/disconnection recovery, permissions on Android versions, and watch frame timing must be validated on devices.
- **First version map is points plus an abstract GPS route on a simple background**, not a tiled street/satellite map (tile data would need an additional offline/static map service).
- The watch does not guarantee offline dictation: Pebble OS dictation and phone support determine STT availability. If STT is unavailable, use watch button edits or Android manual entry.
- **Watch input is not permanently buffered during phone disconnection.** SQLite persists observations after Android receives them, but unsupported/dropped offline watch input needs explicit store-and-forward in a future revision. Always check the acknowledgment.
- Taxonomy shortlist uses **reported observations from the last 30 days** and falls back to all taxa if no nearby list is loaded; it is not a true historical phenology model and can exclude unreported species from the filtered display. Full taxonomy remains searchable by voice/phone.
- Groq parsing is optional; secrets currently live in the app's private SQLite settings, not Android Keystore. Avoid sharing debug database exports or committing credentials to GitHub.
- No direct eBird API submission or guaranteed acceptance; Cornell's importer may ask for location or taxon corrections. Review output and import with current eBird instructions.

## References

- Official eBird import instructions: https://support.ebird.org/en/support/solutions/articles/48000907878
- eBird API v2 docs: https://documenter.getpostman.com/view/664302/S1ENwy59
- Pebble SDK docs: https://developer.repebble.com/
- PebbleKit Android 2: https://github.com/pebble-dev/PebbleKitAndroid2

## Licensing

Source supplied as an editable implementation. MIT license in `LICENSE`. The eBird taxonomy and third-party SDKs are subject to their respective licensors' and services' terms.
