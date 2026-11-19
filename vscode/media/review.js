/* The webview is a renderer. Only the extension host can run Git or change persisted state. */
(() => {
  const api = acquireVsCodeApi(),
    app = document.getElementById("app");
  let state = {},
    ui = {
      search: "",
      picked: [],
      since: false,
      whole: false,
      draftId: null,
      draftEdits: {},
      commentsTab: false,
      filter: "All",
      replies: {},
      deleteId: null,
      edits: {},
      editId: null,
      finish: false,
      summary: "",
      verdict: "COMMENT",
      expanded: false,
      ...api.getState(),
    };
  let targetKey;
  const esc = (value) =>
    String(value ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  const send = (action, data = {}) => api.postMessage({ action, ...data });
  const save = () => api.setState(ui);
  const button = (text, action, cls = "", data = "", disabled = false) =>
    `<button class="${cls}" data-act="${action}" ${data} ${disabled ? "disabled" : ""}>${text}</button>`;
  const verdictName = (v) =>
    ({
      COMMENT: "commented",
      APPROVE: "approved",
      REQUEST_CHANGES: "changes requested",
    })[v];
  function markdown(body) {
    // Escape first; raw HTML and images from PR authors never execute or fetch resources.
    let code = [];
    let text = esc(body.replace(/<!--[\s\S]*?-->/g, "")).replace(
      /```[^\n]*\n([\s\S]*?)```/g,
      (_, s) => `\u0001${code.push(`<pre><code>${s}</code></pre>`) - 1}\u0001`,
    );
    text = text
      .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2">$1</a>')
      .replace(/^#{1,6} (.+)$/gm, "<h3>$1</h3>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/^[-*] (.+)$/gm, "<div>• $1</div>")
      .replace(/\n/g, "<br>");
    return text.replace(/\u0001(\d+)\u0001/g, (_, i) => code[Number(i)]);
  }
  function progress(target) {
    if (target.newCommits)
      return '<span class="pill">↻ New commits since your review</span>';
    const p = target.progress;
    if (!p)
      return target.requested
        ? '<span class="pill request">Review requested</span>'
        : "";
    const done = p.total > 0 && p.viewed === p.total;
    return `<span class="pill ${done ? "done" : ""}">${done ? `✓ Reviewed · ${verdictName(p.verdict) || `${p.viewed} of ${p.total}`}` : `Resume · ${p.viewed} of ${p.total} viewed`}</span>`;
  }
  function list() {
    const shown = (state.targets || []).filter((t) =>
      `${t.title} #${t.pr?.number || ""} ${t.pr?.author?.name || t.pr?.author?.login || ""}`
        .toLowerCase()
        .includes(ui.search.toLowerCase()),
    );
    const section = (title, targets) =>
      !targets.length
        ? ""
        : `<div class="section">${title}</div>${targets.map((t) => `<button class="target" data-act="select" data-key="${esc(t.target.key)}"><span class="avatar">${esc((t.pr?.author?.name || t.pr?.author?.login || "BR").slice(0, 2).toUpperCase())}</span><span class="target-copy"><span class="target-title" title="${esc(t.title)}">${esc(t.title)}</span><span class="meta">${t.pr ? `<span>#${t.pr.number}</span><span>${esc(t.pr.author.name || t.pr.author.login)}</span><span>${t.pr.changedFiles} files</span>` : "Remote branch"}${progress(t)}</span></span></button>`).join("")}`;
    return `<div class="searchbar"><input type="search" id="search" aria-label="Search pull requests" placeholder="Search pull requests" value="${esc(ui.search)}">${button("↻", "refresh", "secondary", 'aria-label="Refresh pull requests"', state.loading)}</div>${state.branchesOnly ? '<div class="notice">Couldn’t get pull requests from GitHub · showing branches</div>' : ""}${
      state.loading
        ? '<p class="empty">Loading pull requests…</p>'
        : shown.length
          ? state.branchesOnly
            ? section("Branches", shown)
            : section(
                "Waiting on you",
                shown.filter((t) => t.requested),
              ) +
              section(
                "Other open",
                shown.filter((t) => !t.requested),
              )
          : `<p class="empty">${ui.search ? "No matching pull requests" : state.branchesOnly ? "No remote branches to review" : "No open pull requests"}</p>`
    }`;
  }
  function detail() {
    const t = state.selected,
      pr = t.pr,
      loaded = state.loaded;
    const option = (control, title, hint) =>
      `<label>${control}<span class="subject">${title}<div class="fineprint">${hint}</div></span></label>`;
    const scope = loaded
      ? `<div class="scope">${option(`<input type="radio" name="scope" data-scope="all" ${!ui.picked.length && !ui.since ? "checked" : ""}>`, "<b>All commits</b>", `The whole PR against ${esc(t.target.base.replace(/^origin\//, ""))}`)}${state.since ? option(`<input type="radio" name="scope" data-scope="since" ${ui.since ? "checked" : ""}>`, "<b>Since your last review</b>", esc(state.since.label)) : ""}${loaded.commits.map((c, i) => option(`<input type="checkbox" data-pick="${i}" ${ui.picked.includes(i) ? "checked" : ""}>`, `<span class="sha">${esc(c.sha.slice(0, 7))}</span> ${esc(c.subject)}`, esc(c.author))).join("")}</div>`
      : '<p class="fineprint">Fetching commits…</p>';
    let start = `<div class="start">${button(t.newCommits ? (state.since ? "Review new commits" : "Review again") : t.progress ? (t.progress.total > 0 && t.progress.viewed === t.progress.total ? "Review again" : "Resume review") : "Start review", "start", "primary", "", !loaded || !loaded.commits.length || state.busy)}<p class="fineprint">${loaded && !loaded.commits.length ? "Nothing to review: no commits beyond the base." : "Opens the PR in this window, read-only."}</p></div>`;
    if (state.park)
      start = `<div class="card"><b>You have ${state.park.files.length} uncommitted changes on ${esc(state.park.branch)}</b><ul>${state.park.files
        .slice(0, 12)
        .map((f) => `<li>${esc(f)}</li>`)
        .join(
          "",
        )}</ul><p class="fineprint">Review Lens parks them in a named stash and puts them back when you exit. Nothing is committed.</p><div class="actions">${button("Park and start review", "parkStart", "primary", "", state.busy)}${button("Cancel", "cancelPark", "secondary", "", state.busy)}</div></div>`;
    return `<div class="detail">${button("‹ All pull requests", "back", "secondary", "", state.busy)}<h2>${esc(t.title)} <span class="fineprint">${pr ? "#" + pr.number : ""}</span></h2>${pr ? `<div class="meta">${esc(pr.author.name || pr.author.login)} wants to merge</div>` : ""}<div class="meta"><span class="branch">${esc(t.target.head)}</span> into <span class="branch">${esc(t.target.base.replace(/^origin\//, ""))}</span></div>${pr?.body ? `<div class="description ${ui.expanded ? "expanded" : ""}">${markdown(pr.body)}</div>${button(ui.expanded ? "Show less" : "Show more", "expand", "link")}` : ""}<div class="stats">${pr ? `<span>${pr.changedFiles} files</span><span class="add">+${pr.additions}</span><span class="del">−${pr.deletions}</span>` : ""}<span>${esc(state.checks)}</span></div>${start}<div class="section scope-heading">What to review</div>${scope}</div>`;
  }
  function finish() {
    const pending = state.pending || 0,
      mark = ui.verdict === "MARK";
    const blocker =
      state.slot?.pending &&
      state.meta?.range.to !== state.slot.head &&
      state.pending -
        (state.meta?.drafts || []).filter((d) => !d.posted && d.replyTo)
          .length >
        0
        ? "Your pending GitHub review targets the latest push. Review up to that push before adding comments."
        : !mark && state.own && ui.verdict !== "COMMENT"
          ? "GitHub only permits a Comment review on your own pull request."
          : !mark &&
              ui.verdict !== "APPROVE" &&
              !ui.summary.trim() &&
              !pending &&
              !state.slot?.pending?.comments
            ? "Add a summary or an inline comment before submitting."
            : "";
    const option = (v, title, hint) =>
      `<label><input type="radio" name="verdict" value="${v}" ${ui.verdict === v ? "checked" : ""} ${state.own && ["APPROVE", "REQUEST_CHANGES"].includes(v) ? "disabled" : ""}><span>${title}<small>${hint}</small></span></label>`;
    return `<section class="finish"><h3>Finish your review</h3>${state.slot?.pending ? `<p class="notice">Your pending GitHub review will be adopted; its ${state.slot.pending.comments} existing comments will also be posted.</p>` : ""}${state.threadError ? `<p class="warning">Threads could not be refreshed. Replies will be attempted and GitHub may refuse the review.</p>` : ""}<p class="fineprint">${state.viewed} of ${state.changes.length} files viewed. ${mark ? "" : pending ? `${pending} pending comments will be posted with it.` : "No inline comments yet."}</p><textarea id="summary" aria-label="Review summary" placeholder="Leave a summary (optional)" ${mark ? "disabled" : ""}>${esc(ui.summary)}</textarea>${option("COMMENT", "Comment", "Feedback without a verdict")}${option("APPROVE", "Approve", "Ready to merge")}${option("REQUEST_CHANGES", "Request changes", "Must be addressed before merging")}<hr>${option("MARK", "Mark as reviewed", "Nothing is posted to GitHub")}${mark && pending ? `<p class="warning">Your ${pending} draft comments won’t be posted; they’re kept for next time.</p>` : ""}${state.waiting ? `<p class="warning">${state.waiting} more drafts wait for the scope they were written in.</p>` : ""}<p id="blocker" class="fineprint">${esc(blocker)}</p><div class="actions">${button("Cancel", "cancelFinish", "secondary", "", state.busy)}${button(mark ? "Finish review" : "Submit to GitHub", "submit", "primary", `id="submit" title="${esc(blocker)}"`, !!blocker || state.busy)}</div></section>`;
  }
  function review() {
    const changes = state.changes || [],
      total = changes.length,
      meta = state.meta;
    if (!meta || state.recovery)
      return `<div class="card"><p>${esc(state.recovery || "Recovery information was found.")}</p>${button("Exit and restore work", "exit", "primary")}</div>`;
    const head = `<div class="review-head"><h2>${esc(meta.target.label)}</h2><progress class="progress" value="${state.viewed}" max="${total || 1}" aria-label="Files viewed"></progress><div class="review-meta"><span>${state.viewed} of ${total} files viewed</span><span>${esc(state.sync)}</span></div><div class="lock">🔒 Read-only · ${esc(meta.scopeLabel)}</div><div class="review-actions">${button("Scope ▾", "scope", "secondary", "", state.busy)}${button("Next unviewed", "next", "secondary", "", state.busy)}${meta.target.prNumber != null ? button(`Finish review${state.pending ? " · " + state.pending : ""}`, "showFinish", "primary", "", state.busy) : ""}${button("Exit", "exit", "secondary", "", state.busy)}</div><div class="segment">${button("Changed files", "changed", !ui.whole && !ui.commentsTab ? "on" : "")}${button("Whole project", "whole", ui.whole && !ui.commentsTab ? "on" : "")}${button("Comments", "comments", ui.commentsTab ? "on" : "")}</div></div>`;
    if (ui.commentsTab)
      return head + (ui.finish ? finish() : "") + commentsPanel();
    const paths = ui.whole
      ? [
          ...new Set([
            ...(state.tracked || []),
            ...changes.filter((c) => c.status === "D").map((c) => c.path),
          ]),
        ].sort()
      : changes.map((c) => c.path);
    let previous;
    const files = paths
      .map((path) => {
        const c = changes.find((c) => c.path === path),
          dir = path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
        const folder =
          dir !== previous && dir
            ? `<div class="folder">▾ ${esc(dir)}</div>`
            : "";
        previous = dir;
        const symbol =
          c?.badge === "viewed" ? "✓" : c?.badge === "changed" ? "↻" : "★";
        return `${folder}<div class="file ${path === state.current ? "current" : ""}">${c ? button(symbol, "toggle", `mark ${c.badge}`, `data-path="${esc(path)}" aria-label="${c.badge === "viewed" ? "Unmark viewed" : "Mark viewed"}: ${esc(path)}"`, state.busy) : '<span class="mark unchanged">·</span>'}${button(esc(path.split("/").pop()), "open", `filename ${c?.status === "D" ? "deleted" : ""} ${!c ? "unchanged" : ""}`, `data-path="${esc(path)}" title="${esc(path)}"`)}${c ? `<small>${c.added === null ? "binary" : `<span class="add">+${c.added}</span> <span class="del">−${c.deleted}</span>`}</small><span class="status">${c.status}</span>${button("⇄", "diff", "diff", `data-path="${esc(path)}" aria-label="Show diff for ${esc(path)}"`)}` : ""}</div>`;
      })
      .join("");
    const drafts = meta.drafts || [];
    return (
      head +
      (ui.finish ? finish() : "") +
      (files || '<p class="empty">No files changed in this scope.</p>') +
      (drafts.length
        ? '<div class="section">Comments</div><div class="drafts">' +
          drafts.map(draftCard).join("") +
          "</div>"
        : "")
    );
  }
  function suggestion(body, original) {
    let cursor = 0,
      html = "";
    for (const match of body.matchAll(
      /^```suggestion\r?\n([\s\S]*?)^```[ \t]*$/gm,
    )) {
      html += markdown(body.slice(cursor, match.index));
      html +=
        '<div class="suggestion"><small>Suggested change</small>' +
        (original ? '<pre class="del">' + esc(original) + "</pre>" : "") +
        '<pre class="add">' +
        esc(match[1]) +
        "</pre></div>";
      cursor = match.index + match[0].length;
    }
    return html + markdown(body.slice(cursor));
  }
  function draftCard(d, index) {
    const host = d.replyTo && state.threads?.find((t) => t.id === d.replyTo);
    const inline = d.replyTo
      ? host?.line != null &&
        host.diffSide === "RIGHT" &&
        host.comments[0]?.commit === state.meta?.range.to
      : state.changes?.some((c) => c.path === d.path && c.newBlob === d.blob);
    const editing = ui.draftId === index;
    return (
      '<div class="draft"><div class="fineprint">' +
      esc(d.path) +
      ":" +
      d.line +
      " · " +
      (d.posted ? "On GitHub" : "Pending") +
      (d.replyTo ? " reply" : "") +
      (!inline && !d.posted ? " · Outside current scope" : "") +
      "</div>" +
      (editing
        ? '<textarea id="draft-edit" aria-label="Edit pending draft" data-draft="' +
          index +
          '">' +
          esc(ui.draftEdits[index] ?? d.body) +
          "</textarea>" +
          button(
            "Save draft",
            "saveDraft",
            "secondary",
            'data-index="' + index + '"',
            state.busy,
          ) +
          " " +
          button("Cancel", "cancelLocalDraft", "secondary")
        : '<div class="draft-body">' +
          suggestion(d.body, state.covered?.[index]) +
          "</div>" +
          (!d.posted
            ? button(
                "Edit",
                inline ? "editDraft" : "editLocalDraft",
                "link",
                'data-index="' + index + '"',
                state.busy,
              ) +
              " · " +
              button(
                "Delete",
                "deleteDraft",
                "link",
                'data-index="' + index + '"',
                state.busy,
              )
            : "")) +
      "</div>"
    );
  }
  function commentsPanel() {
    const filters = ["All", "Open", "Mine", "Pending"];
    const drafts = state.meta?.drafts || [];
    const threads = (state.threads || []).filter(
      (t) =>
        ui.filter === "All" ||
        (ui.filter === "Open" && !t.isResolved) ||
        (ui.filter === "Mine" && t.comments.some((c) => c.mine)),
    );
    const cards =
      ui.filter === "Pending"
        ? ""
        : threads
            .map(
              (t) =>
                '<article class="draft">' +
                '<div class="fineprint">' +
                esc(t.path) +
                ":" +
                esc(
                  t.startLine
                    ? t.startLine + "–" + t.line
                    : t.line || t.originalLine || "?",
                ) +
                " · " +
                (t.isResolved ? "Resolved" : "Open") +
                (t.comments[0]?.commit !== state.meta?.range.to ||
                t.diffSide !== "RIGHT" ||
                t.line == null
                  ? " · Outside current scope"
                  : "") +
                "</div>" +
                (t.isResolved
                  ? "<details><summary>Resolved thread</summary>"
                  : "") +
                t.comments
                  .map(
                    (c) =>
                      '<div class="posted"><b>' +
                      esc(c.author) +
                      "</b>" +
                      (ui.editId === c.id
                        ? '<textarea id="posted-edit" data-edit="' +
                          esc(c.id) +
                          '" aria-label="Edit posted comment">' +
                          esc(ui.edits[c.id] ?? c.body) +
                          "</textarea>" +
                          button(
                            "Save",
                            "savePosted",
                            "secondary",
                            'data-id="' + esc(c.id) + '"',
                            state.busy,
                          ) +
                          " " +
                          button("Cancel", "cancelPosted", "secondary")
                        : '<div class="draft-body">' +
                          suggestion(c.body, t.originalText) +
                          "</div>") +
                      (c.canEdit
                        ? button(
                            "Edit",
                            "editPosted",
                            "link",
                            'data-id="' + esc(c.id) + '"',
                            state.busy,
                          )
                        : "") +
                      " " +
                      (c.canDelete
                        ? button(
                            "Delete",
                            "askDelete",
                            "link",
                            'data-id="' + esc(c.id) + '"',
                            state.busy,
                          )
                        : "") +
                      (ui.deleteId === c.id
                        ? '<div class="warning">Delete this public comment? ' +
                          button(
                            "Delete permanently",
                            "deletePosted",
                            "secondary",
                            'data-id="' + esc(c.id) + '"',
                            state.busy,
                          ) +
                          " " +
                          button("Cancel", "cancelDelete", "secondary") +
                          "</div>"
                        : "") +
                      "</div>",
                  )
                  .join("") +
                (t.isResolved ? "</details>" : "") +
                (t.isResolved
                  ? t.viewerCanUnresolve
                    ? button(
                        "Unresolve",
                        "unresolve",
                        "link",
                        'data-id="' + esc(t.id) + '"',
                        state.busy,
                      )
                    : ""
                  : t.viewerCanResolve
                    ? button(
                        "Resolve",
                        "resolve",
                        "link",
                        'data-id="' + esc(t.id) + '"',
                        state.busy,
                      )
                    : "") +
                (!t.isResolved
                  ? '<textarea id="reply-' +
                    esc(t.id) +
                    '" data-reply="' +
                    esc(t.id) +
                    '" aria-label="Reply to ' +
                    esc(t.path) +
                    '" placeholder="Reply (posted when you finish)">' +
                    esc(ui.replies[t.id] || "") +
                    "</textarea>" +
                    button(
                      "Save reply",
                      "reply",
                      "secondary",
                      'data-id="' + esc(t.id) + '"',
                      state.busy,
                    )
                  : "") +
                "</article>",
            )
            .join("");
    const pending = drafts
      .map((d, index) => ({ d, index }))
      .filter(
        ({ d }) =>
          !d.posted &&
          (ui.filter === "All" ||
            ui.filter === "Mine" ||
            ui.filter === "Pending"),
      )
      .map(({ d, index }) => draftCard(d, index))
      .join("");
    return (
      '<section class="drafts"><div class="segment">' +
      filters
        .map((f) =>
          button(
            f,
            "filter",
            ui.filter === f ? "on" : "",
            'data-filter="' + f + '"',
          ),
        )
        .join("") +
      "</div>" +
      button("Refresh comments", "refreshThreads", "link") +
      (state.threadError
        ? '<p class="warning">' + esc(state.threadError) + "</p>"
        : "") +
      cards +
      pending +
      (!cards && !pending
        ? '<p class="fineprint">No comments in this filter.</p>'
        : "") +
      "</section>"
    );
  }
  function render() {
    const focused = document.activeElement?.id,
      selection = focused && document.activeElement.selectionStart;
    const scroll = window.scrollY;
    app.innerHTML =
      (state.error
        ? `<div class="error" role="alert">${esc(state.error)} ${button("Try again", "refresh", "link")}</div>`
        : "") +
      (state.busy
        ? `<div class="busy" role="status"><span class="spin"></span>${esc(state.step || "Working…")}</div>`
        : "") +
      (state.active ? review() : state.selected ? detail() : list());
    if (focused) {
      const element = document.getElementById(focused);
      if (element) {
        element.focus();
        if (typeof selection === "number")
          element.setSelectionRange(selection, selection);
      }
    }
    window.scrollTo(0, scroll);
    save();
  }
  app.addEventListener("click", (e) => {
    const b = e.target.closest("[data-act]");
    if (!b || b.disabled) return;
    const a = b.dataset.act;
    if (a === "editLocalDraft") {
      ui.draftId = Number(b.dataset.index);
      ui.draftEdits[ui.draftId] = state.meta.drafts[ui.draftId].body;
      render();
    } else if (a === "cancelLocalDraft") {
      ui.draftId = null;
      render();
    } else if (a === "saveDraft")
      send("saveDraft", {
        index: Number(b.dataset.index),
        body: ui.draftEdits[b.dataset.index],
      });
    else if (a === "comments") {
      ui.commentsTab = true;
      render();
    } else if (a === "filter") {
      ui.filter = b.dataset.filter;
      render();
    } else if (a === "askDelete" || a === "cancelDelete") {
      ui.deleteId = a === "askDelete" ? b.dataset.id : null;
      render();
    } else if (a === "reply") {
      send("reply", { id: b.dataset.id, body: ui.replies[b.dataset.id] || "" });
    } else if (a === "resolve" || a === "unresolve")
      send("resolve", { id: b.dataset.id, resolved: a === "resolve" });
    else if (a === "editPosted") {
      ui.editId = b.dataset.id;
      ui.edits[ui.editId] ??= state.threads
        .flatMap((t) => t.comments)
        .find((c) => c.id === ui.editId)?.body;
      render();
    } else if (a === "cancelPosted") {
      ui.editId = null;
      render();
    } else if (a === "savePosted")
      send(a, { id: b.dataset.id, body: ui.edits[b.dataset.id] });
    else if (a === "deletePosted") {
      send(a, { id: b.dataset.id, confirm: true });
      ui.deleteId = null;
    } else if (a === "expand") {
      ui.expanded = !ui.expanded;
      render();
    } else if (a === "whole" || a === "changed") {
      ui.whole = a === "whole";
      ui.commentsTab = false;
      render();
    } else if (a === "showFinish" || a === "cancelFinish") {
      ui.finish = a === "showFinish";
      if (ui.finish) send("prepareFinish");
      render();
    } else if (a === "submit")
      send("finish", { summary: ui.summary, verdict: ui.verdict });
    else if (a === "start" || a === "parkStart")
      send(a, { picked: ui.picked, since: ui.since });
    else
      send(a, {
        key: b.dataset.key,
        path: b.dataset.path,
        index:
          b.dataset.index === undefined ? undefined : Number(b.dataset.index),
      });
  });
  app.addEventListener("change", (e) => {
    if (e.target.dataset.scope) {
      ui.since = e.target.dataset.scope === "since";
      ui.picked = [];
      render();
    } else if (e.target.dataset.pick !== undefined) {
      const i = Number(e.target.dataset.pick);
      ui.since = false;
      if (ui.picked.includes(i)) {
        const newer = ui.picked.filter((n) => n < i);
        ui.picked = newer.length ? newer : ui.picked.filter((n) => n !== i);
      } else {
        const indexes = [...ui.picked, i];
        ui.picked = Array.from(
          { length: Math.max(...indexes) - Math.min(...indexes) + 1 },
          (_, n) => Math.min(...indexes) + n,
        );
      }
      render();
    } else if (e.target.name === "verdict") {
      ui.verdict = e.target.value;
      render();
    }
  });
  app.addEventListener("input", (e) => {
    if (e.target.dataset.draft !== undefined) {
      ui.draftEdits[e.target.dataset.draft] = e.target.value;
      save();
      return;
    }
    if (e.target.dataset.edit) {
      ui.edits[e.target.dataset.edit] = e.target.value;
      save();
      return;
    }
    if (e.target.dataset.reply) {
      ui.replies[e.target.dataset.reply] = e.target.value;
      save();
      return;
    }
    if (e.target.id === "search") {
      ui.search = e.target.value;
      render();
    } else if (e.target.id === "summary") {
      ui.summary = e.target.value;
      render();
    }
  });
  window.addEventListener("message", (e) => {
    if (e.data.type !== "state") return;
    const next = e.data.state;
    if (next.selected?.target.key !== targetKey) {
      targetKey = next.selected?.target.key;
      ui.picked = [];
      ui.since = false;
      ui.expanded = false;
    }
    if (state.active && !next.active) {
      ui.finish = false;
      ui.summary = "";
      ui.verdict = "COMMENT";
    }
    if (next.finish) ui.finish = true;
    if (next.loaded && !state.loaded && next.selected?.newCommits && next.since)
      ui.since = true;
    if (
      !next.busy &&
      !next.error &&
      ui.editId &&
      next.threads?.some((t) =>
        t.comments.some(
          (c) => c.id === ui.editId && c.body === ui.edits[ui.editId],
        ),
      )
    )
      ui.editId = null;
    for (const [id, body] of Object.entries(ui.replies))
      if (
        next.meta?.drafts?.some(
          (d) => d.replyTo === id && d.body === body.trim(),
        )
      )
        ui.replies[id] = "";
    if (
      !next.busy &&
      !next.error &&
      ui.draftId != null &&
      next.meta?.drafts?.[ui.draftId]?.body === ui.draftEdits[ui.draftId]
    )
      ui.draftId = null;
    state = next;
    render();
  });
  send("ready");
})();
