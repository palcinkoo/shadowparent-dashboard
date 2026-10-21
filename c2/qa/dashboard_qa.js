/* Multi-profile dashboard QA harness.
 *
 * Renders the control panel under real device emulation (UA + touch + DPR) for a
 * range of phones, tablets and desktop sizes, walks every view, and reports:
 *   - console / page / network errors
 *   - horizontal overflow and the elements causing it
 *   - overlapping elements in the top bar
 *   - tap targets below 40px on touch devices
 *   - broken text ("pred pred", "Android ?", "undefined", "NaN")
 *
 * Usage:
 *   node c2/qa/dashboard_qa.js
 *   C2_BASE=http://127.0.0.1:8000 C2_USER=admin C2_PASS=secret node c2/qa/dashboard_qa.js
 */
const puppeteer = require("puppeteer");
const fs = require("fs");
const path = require("path");

const BASE = (process.env.C2_BASE || "https://shadowparent-c2.onrender.com").replace(/\/$/, "");
const USER = process.env.C2_USER || "admin";
const PASS = process.env.C2_PASS || "sp-3JRC56yXBZrvFdv";
const CHROME = process.env.CHROME || "/usr/bin/chromium";
const OUT = process.env.QA_OUT || path.join(__dirname, "out");
const VIEWS = ["overview", "location", "keylog", "messages", "calls", "contacts", "media", "files", "commands"];

const ANDROID_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
const DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

const ALL_PROFILES = [
  { name: "android-small",  width: 360, height: 740,  dpr: 3,   mobile: true,  touch: true,  ua: ANDROID_UA },
  { name: "android-phone",  width: 412, height: 915,  dpr: 2.625, mobile: true, touch: true, ua: ANDROID_UA },
  { name: "android-large",  width: 430, height: 932,  dpr: 3,   mobile: true,  touch: true,  ua: ANDROID_UA },
  { name: "ios-small",      width: 375, height: 667,  dpr: 2,   mobile: true,  touch: true,  ua: IOS_UA },
  { name: "ios-phone",      width: 390, height: 844,  dpr: 3,   mobile: true,  touch: true,  ua: IOS_UA },
  { name: "tablet",         width: 820, height: 1180, dpr: 2,   mobile: true,  touch: true,  ua: IOS_UA },
  { name: "desktop",        width: 1440, height: 900, dpr: 1,   mobile: false, touch: false, ua: DESKTOP_UA },
  { name: "wide",           width: 1920, height: 1080, dpr: 1,  mobile: false, touch: false, ua: DESKTOP_UA }
];
const only = (process.env.QA_PROFILES || "").split(",").map(s => s.trim()).filter(Boolean);
const PROFILES = only.length ? ALL_PROFILES.filter(p => only.includes(p.name)) : ALL_PROFILES;

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function login(page) {
  await page.goto(BASE + "/login", { waitUntil: "networkidle2", timeout: 60000 });
  await page.type("input[name=username]", USER);
  await page.type("input[name=password]", PASS);
  await Promise.all([
    page.waitForNavigation({ waitUntil: "networkidle2", timeout: 60000 }),
    page.click("button[type=submit], input[type=submit]")
  ]);
}

async function pickDevice(page) {
  const forced = process.env.QA_DEVICE || "";
  const devId = await page.evaluate(async forced => {
    if (forced) return forced;
    const r = await fetch("/api/devices");
    const d = await r.json();
    return Array.isArray(d) && d.length ? d[0].device_id : null;
  }, forced);
  if (!devId) return null;
  await page.evaluate(id => localStorage.setItem("sp_device", id), devId);
  return devId;
}

async function audit(page) {
  return page.evaluate(() => {
    const rect = el => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return null;
      return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    };
    const visible = el => {
      const s = getComputedStyle(el);
      return s.display !== "none" && s.visibility !== "hidden" && parseFloat(s.opacity) > 0.05;
    };

    const vw = window.innerWidth;
    const doc = document.documentElement;
    const overflow = {
      scrollWidth: doc.scrollWidth,
      clientWidth: doc.clientWidth,
      horizontal: doc.scrollWidth > vw + 2,
      culprits: []
    };
    for (const el of document.querySelectorAll("body *")) {
      if (!visible(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      if (r.right > vw + 2 || r.left < -2) {
        overflow.culprits.push({
          sel: el.tagName.toLowerCase() + (el.id ? "#" + el.id : "") + (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\s+/).join(".") : ""),
          left: Math.round(r.left),
          right: Math.round(r.right)
        });
      }
    }
    overflow.culprits = overflow.culprits.slice(0, 12);

    const top = document.querySelector(".top");
    const headerItems = top
      ? [...top.children].filter(visible).map(el => ({ el, r: el.getBoundingClientRect(), label: el.id || el.className || el.tagName }))
      : [];
    const overlaps = [];
    for (let i = 0; i < headerItems.length; i++) {
      for (let j = i + 1; j < headerItems.length; j++) {
        const a = headerItems[i].r, b = headerItems[j].r;
        const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w > 2 && h > 2) {
          overlaps.push({ a: headerItems[i].label, b: headerItems[j].label, area: Math.round(w * h) });
        }
      }
    }

    const small = [];
    for (const el of document.querySelectorAll("button, a[href], input, select, textarea, [role=button]")) {
      if (!visible(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.height < 40 || r.width < 40) {
        small.push({ sel: el.tagName.toLowerCase() + (el.id ? "#" + el.id : "") + (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\s+/)[0] : ""), w: Math.round(r.width), h: Math.round(r.height) });
      }
    }

    /* An <svg> without an explicit size stretches to the default 300x150 and
       blows up the card it lives in - flag anything that renders too big. */
    const oversized = [];
    for (const el of document.querySelectorAll("svg")) {
      if (!visible(el)) continue;
      // Leaflet draws its vector layers into a full-map <svg> - not an icon.
      if (el.closest(".leaflet-container")) continue;
      const r = el.getBoundingClientRect();
      if (r.width > 48 || r.height > 48) {
        const parent = el.parentElement;
        oversized.push({
          sel: (parent ? parent.tagName.toLowerCase() + (parent.className && typeof parent.className === "string" ? "." + parent.className.trim().split(/\s+/)[0] : "") : "?"),
          w: Math.round(r.width), h: Math.round(r.height)
        });
      }
    }

    const text = document.body.innerText || "";
    const textBugs = [];
    if (text.includes("pred pred")) textBugs.push("double 'pred pred' in subtitle");
    if (text.includes("Android ?")) textBugs.push("missing Android release ('Android ?')");
    if (/\bundefined\b/.test(text)) textBugs.push("'undefined' rendered in UI");
    if (/\bNaN\b/.test(text)) textBugs.push("'NaN' rendered in UI");

    const tiles = [...document.querySelectorAll("#map img.leaflet-tile")];
    const map = {
      present: !!document.getElementById("map"),
      tiles: tiles.length,
      tilesLoaded: tiles.filter(t => t.complete && t.naturalWidth > 0).length,
      dark: document.getElementById("map") ? document.getElementById("map").classList.contains("tiles-dark") : false
    };

    return {
      overflow,
      oversizedIcons: oversized.slice(0, 12),
      overlaps,
      smallTargets: small.slice(0, 20),
      textBugs,
      map,
      players: {
        audio: document.querySelectorAll("audio").length,
        video: document.querySelectorAll("video").length
      },
      rows: {
        loc: document.querySelectorAll("#locRows tr, #locRows .row").length,
        key: document.querySelectorAll("#keyRows tr, #keyRows .row").length,
        sms: document.querySelectorAll("#smsRows tr, #smsRows .row").length,
        calls: document.querySelectorAll("#callRows tr, #callRows .row").length,
        contacts: document.querySelectorAll("#contactRows tr, #contactRows .row").length,
        media: document.querySelectorAll("#gallery .gitem, #gallery > *").length,
        files: document.querySelectorAll("#fileRows tr, #fileRows .row").length,
        cmdGroups: document.querySelectorAll("#cmdGroups .card, #cmdGroups > *").length
      },
      head: {
        name: (document.getElementById("devName") || {}).textContent,
        sub: (document.getElementById("devSub") || {}).textContent,
        live: (document.getElementById("livePill") || {}).textContent
      }
    };
  });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    headless: "new",
    executablePath: CHROME,
    args: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--font-render-hinting=none"]
  });

  const report = { base: BASE, when: new Date().toISOString(), profiles: [] };

  for (const p of PROFILES) {
    const page = await browser.newPage();
    await page.setViewport({ width: p.width, height: p.height, deviceScaleFactor: p.dpr, isMobile: p.mobile, hasTouch: p.touch });
    await page.setUserAgent(p.ua);

    const errors = [];
    const netFails = [];
    page.on("pageerror", e => errors.push("pageerror: " + e.message));
    page.on("console", m => { if (m.type() === "error") errors.push("console: " + m.text()); });
    page.on("requestfailed", r => netFails.push(r.url() + " :: " + (r.failure() || {}).errorText));
    page.on("response", r => { if (r.status() >= 400) netFails.push(r.status() + " " + r.url()); });

    const entry = { profile: p, views: {}, errors: [], netFails: [] };

    try {
      await login(page);
      await page.goto(BASE + "/dashboard", { waitUntil: "networkidle2", timeout: 60000 });
      await sleep(1500);
      entry.device = await pickDevice(page);
      await page.reload({ waitUntil: "networkidle2", timeout: 60000 });
      await sleep(2500);

      // off-canvas sidebar check on narrow screens
      entry.sidebar = await page.evaluate(() => {
        const s = document.getElementById("side");
        if (!s) return { present: false };
        const r = s.getBoundingClientRect();
        const cs = getComputedStyle(s);
        return { present: true, left: Math.round(r.left), width: Math.round(r.width), transform: cs.transform, position: cs.position };
      });

      for (const v of VIEWS) {
        await page.evaluate(view => {
          const a = [...document.querySelectorAll(".nav a")].find(x => x.dataset.view === view);
          if (a) a.click();
        }, v);
        await sleep(v === "location" ? 4500 : 2000);
        const a = await audit(page);
        a.shot = path.join(OUT, p.name + "-" + v + ".png");
        await page.screenshot({ path: a.shot });
        entry.views[v] = a;
      }

      // global text / errors
      entry.errors = errors;
      entry.netFails = netFails.slice(0, 15);
    } catch (e) {
      entry.fatal = e.message;
    }

    report.profiles.push(entry);
    await page.close();
    const first = entry.views.overview || {};
    console.log("[" + p.name + "] overflow=" + (first.overflow && first.overflow.horizontal) +
      " overlaps=" + ((first.overlaps || []).length) +
      " bigIcons=" + ((first.oversizedIcons || []).length) +
      " textBugs=" + JSON.stringify((first.textBugs || [])) +
      " errors=" + entry.errors.length +
      (entry.fatal ? " FATAL=" + entry.fatal : ""));
  }

  await browser.close();
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(report, null, 2));
  console.log("\nreport: " + path.join(OUT, "report.json"));
  console.log("shots:  " + OUT);
})().catch(e => { console.error("FAIL", e); process.exit(1); });
