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
      const cookies = await browser.cookies.getAll({});
      await postSecureMessage({
        action: "COOKIES_DUMP",
        requestId: message.requestId,
        cookies: cookies || []
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
      for (const c of cookies) {
        try {
          if (!c.domain || !c.name) continue;
          const cleanDomain = c.domain.startsWith(".") ? c.domain.substring(1) : c.domain;
          const protocol = c.secure ? "https://" : "http://";
          const path = c.path || "/";
          const cookieUrl = `${protocol}${cleanDomain}${path}`;
          
          const details = {
            url: cookieUrl,
            name: c.name,
            value: c.value || "",
            domain: c.domain,
            path: path,
            secure: !!c.secure,
            httpOnly: !!c.httpOnly
          };
          if (c.expirationDate) {
            details.expirationDate = c.expirationDate;
          }
          if (c.sameSite) {
            details.sameSite = c.sameSite;
          }
          await browser.cookies.set(details);
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
