# Mythings Scraper (standalone APK)

An installable Android app that drives the Mythings app's own screens -
search for a merchant, handle it being "closed" and buried lower in the
results, open its store page, scroll through every catalog tab, and read
back item name / price / photo from the product cards - without tapping
into any individual product. Results append to a CSV on-device, which you
export via Android's share sheet.

## What this is, technically, and what it isn't

This uses Android's **Accessibility Service** API - the same mechanism
screen readers and automation apps (Tasker, Automate) use. It reads the
on-screen accessibility tree and simulates taps/scrolls/text-entry. It does
**not** call Mythings' private backend API, does **not** touch SSL/
certificate pinning, and needs **no root**. It only ever reads what a
logged-in human would see by browsing the app themselves, and only taps:
the search box, a matched store's row, and category tabs - never a product
card or its "+" button.

**Before this runs against more than a couple of merchants, or gets shared
with anyone else at Talabat:** this automates interaction with a
competitor's app for pricing intelligence, which is exactly the kind of
thing most companies route through legal/compliance or a vetted
third-party data vendor - partly because consumer app Terms of Service
commonly prohibit bots/automated access even when no security control is
being defeated. Worth a quick check-in before this becomes a standing tool,
especially packaged as an installable app rather than a one-off script.

## What I built this from

You attached real screenshots of: the home screen, the search screen, the
"Shadi Pet Shop" search results (showing it marked **Closed** and appearing
several rows down, below other matches - exactly the edge case you
described), and the store page itself (category tabs like "Dry Cat Food" /
"Wet Cat Food" / "Cat Treat", a 103-item count, and product cards with
name + price in "NN.NN JD" format + photo + a "+" add button).

Things that came directly from those screenshots and shaped the code:
- Prices render as either one combined text node ("50.00 JD") or two
  separate ones ("50.00" then "JD") - both are handled.
- A store's catalog is split across category tabs, not one long list - so
  "scrape the catalog" means looping every tab, not just the first one.
- The closed/open-soon badge sits on the store's logo in search results -
  captured into a `merchant_status` column rather than used to change
  scraping behavior (a closed shop's catalog is still fully browsable, as
  your last screenshot shows).
- Product cards never got clicked into: the scraper only ever reads text
  and the photo's on-screen position from each card's subtree, and the
  only clicks inside Mythings are on the search box, a matched store row,
  and category tabs.

I don't have your actual two other test merchants' screens, so one
assumption is baked in and worth checking on your first real run: that
**every store's catalog screen looks structurally like Shadi Pet Shop's**
(tabs up top, a plain vertical list of cards below). If some stores use a
different layout (e.g. a grid instead of a list, or no tabs), the card
reader and tab loop should still mostly work since they're heuristic
(looking for "a price-shaped text near a name-shaped text", not a fixed
layout), but it's untested against that variation.

## Setup - no Android Studio / no admin rights needed

If you can't install Android Studio (admin-restricted machine), you don't
need it. GitHub will build the APK for you in the cloud; you only ever use
a browser on your PC and a file download on the phone.

1. **Find Mythings' real Android package name, from your phone, no PC
   needed:** open the Play Store app → search "Mythings" → open the
   listing → tap the **Share** icon → it generates a link shaped like
   `https://play.google.com/store/apps/details?id=com.something.here`.
   The `id=...` part is the package name you need. (I tried to look this
   up myself first so you wouldn't have to - the only `com.mythings.app`
   listing I could confirm turned out to be an unrelated app with the
   same name, so this needs to come from your actual install.)

2. **Create a free GitHub account** at github.com if you don't have one
   (just a website signup, nothing to install).

3. **Create a new repository**: github.com → the "+" icon top-right →
   "New repository" → give it any name (e.g. `mythings-scraper`) → you can
   make it Private → "Create repository".

4. **Edit the 3 placeholders before uploading** (do this on your PC in a
   plain text editor like Notepad - no IDE needed): open each of these
   files from the folder I gave you and replace `com.mythings.REPLACE_ME`
   with the real package name from step 1:
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/res/xml/accessibility_service_config.xml`
   - `app/src/main/java/com/example/mythingsscraper/ScraperAccessibilityService.kt`

5. **Upload the whole `mythings-scraper-android` folder**: on your new
   repo's GitHub page → "Add file" → "Upload files" → drag the entire
   folder in (GitHub preserves the subfolder structure) → scroll down →
   "Commit changes". This includes a `.github/workflows/build.yml` file
   I already added, which tells GitHub to build the APK automatically.

6. **Watch it build**: go to the "Actions" tab on your repo. A run should
   start automatically within a minute of your upload (or click
   "Run workflow" if it didn't). It takes a few minutes. Green check =
   success.

7. **Download the APK**: click the finished run → scroll to "Artifacts" →
   download `mythings-scraper-debug-apk` (a zip containing
   `app-debug.apk`).

8. **Get it onto the phone**: easiest is opening that same GitHub Actions
   page in the phone's browser and downloading it there directly: or
   download on the PC and send it to the phone via email/Drive/WhatsApp/USB
   file copy - any method works, no adb required.

9. **Install it on the phone**: open the downloaded `app-debug.apk` file
   (e.g. from the Downloads app or whatever downloaded it). Android will
   ask permission to "install unknown apps" from that one source app -
   allow it, then tap Install. This is a phone-side permission only; it
   doesn't touch your PC at all.

10. On the phone: open the new "Mythings Scraper" app → tap
    **"Open Accessibility Settings"** → find it in the list → turn it on
    (Android requires this to be a manual toggle; it can't be auto-granted).

11. Back in the app: type a merchant name (e.g. "Shadi Pet Shop") → **Add**
    → repeat for more merchants if you want a batch → **Start scraping
    queue**.

12. Watch the phone - it will take over the screen while it works; don't
    touch it mid-run. Progress shows in the app's status log.

13. When done (or partway through, it appends as it goes), tap **Share /
    export CSV** to send `mythings_prices.csv` to yourself (email, Drive,
    WhatsApp, whatever's on the share sheet) - open it directly in Excel.

**Making a change later (e.g. I send you a fix to `CardExtractor.kt`):**
edit that one file in the GitHub web UI directly (click the file → pencil
icon → edit → commit), which re-triggers the Actions build automatically -
repeat from step 7.

## Setup - if you do have Android Studio available

1. Open this folder in Android Studio (File → Open), let Gradle sync,
   plug in your Android phone (Developer Options → USB debugging on),
   and hit Run - this both builds and installs it in one step, faster to
   iterate on than the GitHub route above.
2. Same steps 10-13 above from there.

## Output

`data` lives in the app's private external-files folder and isn't
browsable from the Files app directly - always use the in-app **Share /
export CSV** button to get it out.

| column | meaning |
|---|---|
| `merchant` | the name you searched for |
| `category` | which catalog tab the item came from (e.g. "Dry Cat Food") |
| `item_name` | best-effort product name - spot-check these |
| `price_raw` | price text as shown, e.g. "50.00 JD" |
| `image_path` | on-device file path to a cropped screenshot of that item's photo (not a URL - Accessibility Service can only see pixels, not network requests, so there's no way to grab the actual CDN image URL without the API-level access this project deliberately avoids) |
| `merchant_status` | `open`, `closed`, or `open_soon`, read from the search-result badge |
| `scraped_at_iso` | timestamp |

Product images aren't pulled out as files automatically from the share
sheet - they're JPEGs saved under the app's `images/` folder on the phone.
If you want them bundled, use a file manager app (or `adb pull`) to grab
the whole `Android/data/com.example.mythingsscraper/files/` folder.

## Batch scraping more merchants later

Already built in: add as many merchant names as you want to the queue
before hitting Start, and it processes them one after another, appending
to the same CSV each time. No code changes needed to scale from one
merchant to a list.

## Where this will need tuning

The tab/scroll/card-matching logic is heuristic (based on text shape, node
size and position) rather than hardcoded to exact resource IDs, because
every store's catalog can look slightly different and I don't have IDs to
hardcode against anyway. That makes it more resilient to small layout
differences between stores, but also means:
- If a merchant genuinely doesn't exist or is misspelled, after trying
  every visible result-category tab it'll log "not found" and move on -
  check the spelling against what you saw in the app.
- If scraping comes back oddly matched (e.g. "UNKNOWN_ITEM", or weight
  text picked up as the price), check the status log for which card that
  happened on, and tell me what the name/price text actually looked like -
  I can tighten `CardExtractor.kt`'s matching rules.
- Android version matters: screenshot-based image capture needs Android
  11+ (API 30). Everything else (search, scroll, read, tab-switch) works
  on older versions too.

## Verifying it's reading your real UI correctly

Even though this ships as its own app rather than through Appium, you can
still use **Android Studio's Layout Inspector** (Running Devices → Layout
Inspector, with Mythings in the foreground) just to look at the live
accessibility tree while debugging - it doesn't need to be the thing doing
the automation to be useful for figuring out why a selector missed.
