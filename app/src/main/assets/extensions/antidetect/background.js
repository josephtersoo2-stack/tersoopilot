let activeProfileConfig = null;
const pendingResolvers = [];

const BRIDGE_SECRET = "tersoo_antidetect_ext_bridge_secret_v1";

async function computeHmac(data) {
  try {
    const enc = new TextEncoder();
    const key = await crypto.subtle.importKey(
      "raw",
      enc.encode(BRIDGE_SECRET),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign", "verify"]
    );
    const sig = await crypto.subtle.sign("HMAC", key, enc.encode(data));
    return Array.from(new Uint8Array(sig)).map(b => b.toString(16).padStart(2, '0')).join('');
  } catch (e) {
    return "";
  }
}

async function verifyInbound(message) {
  if (!message) return false;
  // If signature is present, verify authenticity & timestamp
  if (message.signature && message.timestamp && message.message_id) {
    if (Math.abs(Date.now() - message.timestamp) > 60000) return false;
    const toSign = `${message.message_id}:${message.timestamp}:${message.action || 'UNKNOWN'}`;
    const computed = await computeHmac(toSign);
    return computed === message.signature;
  }
  // Allow graceful fallback if legacy envelope without signature
  return true;
}

async function postSecureMessage(payload) {
  try {
    const message_id = (typeof crypto !== 'undefined' && crypto.randomUUID) 
      ? crypto.randomUUID() 
      : (Date.now() + "_" + Math.random());
    const timestamp = Date.now();
    const action = payload.action || "UNKNOWN";
    payload.message_id = message_id;
    payload.timestamp = timestamp;
    payload.signature = await computeHmac(`${message_id}:${timestamp}:${action}`);
  } catch (e) {}
  port.postMessage(payload);
}

// Connect to Kotlin via native messaging
const port = browser.runtime.connectNative("antidetect_bridge");

port.onMessage.addListener(async (message) => {
  if (!message) return;
  const isValid = await verifyInbound(message);
  if (!isValid) {
    console.warn("Rejected unauthenticated port message:", message);
    return;
  }

  if (message.action === "APPLY_PROFILE") {
    activeProfileConfig = message.payload;
    while (pendingResolvers.length > 0) {
      const resolve = pendingResolvers.shift();
      resolve({ config: activeProfileConfig });
    }
  } else if (message.action === "GET_COOKIES") {
    try {
      let allCookies = [];
      const seen = new Set();

      // 1. Enumerate all active cookie stores (contextual identities / containers)
      try {
        const stores = await browser.cookies.getAllCookieStores();
        if (stores && stores.length > 0) {
          for (const store of stores) {
            try {
              const storeCookies = await browser.cookies.getAll({ storeId: store.id });
              if (storeCookies && storeCookies.length > 0) {
                for (const c of storeCookies) {
                  const key = `${c.domain}|${c.name}|${c.path}|${c.storeId || store.id}`;
                  if (!seen.has(key)) {
                    seen.add(key);
                    allCookies.push(c);
                  }
                }
              }
            } catch (_) {}
          }
        }
      } catch (_) {}

      // 2. Fallback to default store if stores iteration returned nothing
      if (allCookies.length === 0) {
        try {
          const defaultCookies = await browser.cookies.getAll({});
          if (defaultCookies && defaultCookies.length > 0) {
            allCookies = defaultCookies;
          }
        } catch (_) {}
      }

      await postSecureMessage({
        action: "COOKIES_DUMP",
        requestId: message.requestId,
        cookies: allCookies || []
      });
    } catch (e) {
      await postSecureMessage({
        action: "COOKIES_DUMP",
        requestId: message.requestId,
        error: e ? e.message : "Error reading cookies",
        cookies: []
      });
    }
  } else if (message.action === "SET_COOKIES") {
    try {
      const cookies = message.cookies || [];
      let stores = [];
      try {
        stores = await browser.cookies.getAllCookieStores();
      } catch (_) {}

      for (const c of cookies) {
        try {
          const rawDomain = c.domain || c.host || "";
          const name = c.name || "";
          if (!rawDomain || !name) continue;

          const cleanDomain = rawDomain.startsWith(".") ? rawDomain.substring(1) : rawDomain;
          const isSec = !!(c.secure || c.isSecure);
          const protocol = isSec ? "https://" : "http://";
          const path = c.path || "/";
          const cookieUrl = `${protocol}${cleanDomain}${path}`;

          const details = {
            url: cookieUrl,
            name: name,
            value: c.value || "",
            domain: rawDomain,
            path: path,
            secure: isSec,
            httpOnly: !!(c.httpOnly || c.isHttpOnly)
          };

          if (c.expirationDate) {
            details.expirationDate = c.expirationDate;
          } else if (c.expiry) {
            details.expirationDate = c.expiry;
          }
          if (c.sameSite !== undefined) {
            details.sameSite = c.sameSite;
          }

          // Apply to default cookie store
          await browser.cookies.set(details);

          // Also set into any active container stores
          if (stores && stores.length > 0) {
            for (const st of stores) {
              if (st.id && st.id !== "firefox-default") {
                try {
                  await browser.cookies.set({ ...details, storeId: st.id });
                } catch (_) {}
              }
            }
          }
        } catch (err) {
          // ignore individual cookie set failure
        }
      }
      await postSecureMessage({
        action: "COOKIES_RESTORED",
        requestId: message.requestId,
        count: cookies.length
      });
    } catch (e) {
      // ignore
    }
  }
});

browser.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.type === "FETCH_CONFIG") {
    if (activeProfileConfig) {
      sendResponse({ config: activeProfileConfig });
    } else {
      pendingResolvers.push(sendResponse);
      return true; // Keep sendResponse open asynchronously
    }
  }
  return true;
});
