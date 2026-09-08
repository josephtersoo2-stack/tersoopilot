let activeProfileConfig = null;
const pendingResolvers = [];

// Connect to Kotlin via native messaging
const port = browser.runtime.connectNative("antidetect_bridge");

port.onMessage.addListener(async (message) => {
  if (!message) return;

  if (message.action === "APPLY_PROFILE") {
    activeProfileConfig = message.payload;
    while (pendingResolvers.length > 0) {
      const resolve = pendingResolvers.shift();
      resolve({ config: activeProfileConfig });
    }
  } else if (message.action === "GET_COOKIES") {
    try {
      const cookies = await browser.cookies.getAll({});
      port.postMessage({
        action: "COOKIES_DUMP",
        requestId: message.requestId,
        cookies: cookies || []
      });
    } catch (e) {
      port.postMessage({
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
      port.postMessage({
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
