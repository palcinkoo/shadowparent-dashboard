# Dashboard QA harness

Renders the control panel under real device emulation (user agent, touch flag,
device pixel ratio) for a range of phones, tablets and desktop widths, walks
every view and reports what a human would notice:

- console errors, uncaught page errors, failed / 4xx / 5xx requests
- horizontal overflow and the elements that cause it
- overlapping elements in the top bar
- `<svg>` without an explicit size (renders at the 300x150 default and blows
  the card apart)
- tap targets below 40px on touch devices
- broken text: `pred pred`, `Android ?`, `undefined`, `NaN`
- map tiles: how many loaded, whether the dark theme is active
- media players: how many `<audio>` / `<video>` elements are on the page

## Run

```bash
npm i puppeteer                     # once
node c2/qa/dashboard_qa.js
```

Against a local instance:

```bash
C2_BASE=http://127.0.0.1:8099 C2_USER=admin C2_PASS=secret \
QA_DEVICE=<device_id> node c2/qa/dashboard_qa.js
```

| Variable | Meaning |
|---|---|
| `C2_BASE` | dashboard URL (default `https://shadowparent-c2.onrender.com`) |
| `C2_USER` / `C2_PASS` | login credentials |
| `QA_DEVICE` | force a specific device id instead of the first one |
| `QA_PROFILES` | comma separated profile names, e.g. `android-phone,desktop` |
| `QA_OUT` | output directory (default `c2/qa/out`) |
| `CHROME` | Chromium/Chrome binary (default `/usr/bin/chromium`) |

Screenshots and `report.json` land in `c2/qa/out/`.

## Profiles

`android-small` (360x740), `android-phone` (412x915), `android-large` (430x932),
`ios-small` (375x667), `ios-phone` (390x844), `tablet` (820x1180),
`desktop` (1440x900), `wide` (1920x1080).

## Test data

The media views need at least one uploaded file, otherwise the gallery is
empty and no player is rendered. A throwaway file is enough - it can be posted
straight to the upload endpoint:

```bash
curl -X POST "$C2_BASE/upload" -H "X-Key: $API_KEY" \
  -F "meta=<device_id>|mic|rec_test.wav" -F "f=@rec_test.wav"
```
