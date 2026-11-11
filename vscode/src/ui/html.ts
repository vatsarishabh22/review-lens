export function sidebarHtml(
  style: string,
  script: string,
  csp: string,
  nonce: string,
) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${csp}; script-src 'nonce-${nonce}'; img-src ${csp} data:;"><link rel="stylesheet" href="${style}"><title>Review Lens</title></head><body><main id="app" aria-live="polite"><p class="empty">Loading Review Lens…</p></main><script nonce="${nonce}" src="${script}"></script></body></html>`;
}
