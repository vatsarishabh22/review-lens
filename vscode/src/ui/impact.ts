import * as vscode from "vscode";
import { createHash } from "node:crypto";
import * as path from "node:path";
import { readFileSync } from "node:fs";
import { promises as fs } from "node:fs";
import { ReviewService } from "../services/review";
import { Hunk } from "../core/parsers";
import { meaningful } from "../core/impact";

export interface ImpactPlace {
  key: string;
  path: string;
  line: number;
  range: vscode.Range;
  uri: vscode.Uri;
  test: boolean;
  snippet: string;
}
interface FunctionSymbol {
  symbol: vscode.DocumentSymbol;
  name: string;
}
interface ImpactEntry {
  key: string;
  name: string;
  hash: string;
  uri: vscode.Uri;
  line: number;
  kind: string;
  places: ImpactPlace[];
  status?: string;
}
const functions = new Set([
  vscode.SymbolKind.Function,
  vscode.SymbolKind.Method,
  vscode.SymbolKind.Constructor,
]);
function flatten(
  symbols: vscode.DocumentSymbol[],
  parent = "",
): FunctionSymbol[] {
  return symbols.flatMap((symbol) => {
    const name = parent ? `${parent}.${symbol.name}` : symbol.name;
    return [{ symbol, name }, ...flatten(symbol.children ?? [], name)];
  });
}
export function isTestFile(file: string) {
  return (
    /(^|\/)(tests?|__tests__)\//i.test(file) ||
    /(^|\/)(test_[^/]+|[^/]+_test)\.py$|Test\.(kt|java)$|\.(test|spec)\.[cm]?[jt]sx?$|_test\.go$/i.test(
      file,
    )
  );
}
export class ImpactMap implements vscode.CodeLensProvider, vscode.Disposable {
  private readonly changes = new vscode.EventEmitter<void>();
  readonly onDidChangeCodeLenses = this.changes.event;
  private readonly controller = vscode.comments.createCommentController(
    "review-lens-impact",
    "Review Lens Impact",
  );
  private readonly entries = new Map<string, ImpactEntry[]>();
  private readonly pending = new Set<string>();
  private readonly cards = new Map<string, vscode.CommentThread>();
  private readonly subscriptions: vscode.Disposable[] = [];
  private readonly catalog: {
    name: string;
    extensions: string[];
    filenames?: string[];
  }[];
  private readonly retries = new Map<string, number>();
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();
  private scope = "";
  private dynamicWarningShown = false;
  private generation = 0;
  constructor(
    readonly service: ReviewService,
    readonly extensionUri: vscode.Uri,
  ) {
    this.catalog = JSON.parse(
      readFileSync(
        vscode.Uri.joinPath(extensionUri, "media/code-files.json").fsPath,
        "utf8",
      ),
    ).languages;
    this.subscriptions.push(
      vscode.languages.registerCodeLensProvider({ scheme: "file" }, this),
      this.controller,
      this.changes,
    );
    const change = () => {
      const scope = service.meta
        ? `${service.meta.range.from}:${service.meta.range.to}`
        : "";
      if (this.scope !== scope) {
        this.scope = scope;
        this.generation++;
        this.retries.clear();
        service.impactAnalysisComplete = false;
        service.impactKeys.clear();
        this.entries.clear();
        this.pending.clear();
        this.cards.forEach((c) => c.dispose());
        this.cards.clear();
      }
      if (!service.active) this.dynamicWarningShown = false;
      this.changes.fire();
      if (service.active) void this.schedule();
    };
    service.on("change", change);
    this.subscriptions.push({ dispose: () => service.off("change", change) });
    this.subscriptions.push(
      vscode.window.onDidChangeActiveTextEditor(() => {
        if (service.active) void this.schedule();
      }),
    );
  }
  async refresh() {
    this.generation++;
    this.retries.clear();
    this.service.impactAnalysisComplete = false;
    this.entries.clear();
    this.pending.clear();
    this.cards.forEach((c) => c.dispose());
    this.cards.clear();
    await this.schedule();
    this.changes.fire();
  }
  private async schedule() {
    const generation = this.generation;
    const current = vscode.window.activeTextEditor?.document.uri;
    if (current?.scheme === "file") await this.compute(current);
    for (const change of this.service.changes) {
      if (!this.service.meta) break;
      if (change.status !== "D")
        await this.compute(vscode.Uri.file(this.service.git.file(change.path)));
    }
    if (generation === this.generation)
      this.service.impactAnalysisComplete =
        !this.pending.size &&
        [...this.entries.values()].every((entries) =>
          entries.every((entry) => !entry.status),
        );
  }
  private language(uri: vscode.Uri) {
    const ext = path.extname(uri.fsPath).slice(1).toLowerCase();
    return this.catalog.find(
      (l) =>
        l.extensions.includes(ext) ||
        l.filenames?.includes(path.basename(uri.fsPath)),
    );
  }
  private async symbols(
    uri: vscode.Uri,
  ): Promise<FunctionSymbol[] | undefined> {
    const symbols = await vscode.commands.executeCommand<
      vscode.DocumentSymbol[] | vscode.SymbolInformation[]
    >("vscode.executeDocumentSymbolProvider", uri);
    if (!symbols?.length) return undefined;
    // Flat SymbolInformation has no reliable declaration extent or selection range: don't invent it.
    if (symbols.some((s) => !("selectionRange" in s))) return undefined;
    return flatten(symbols as vscode.DocumentSymbol[]);
  }
  private inHunk(symbol: vscode.DocumentSymbol, hunks: Hunk[]) {
    return hunks.some((h) =>
      h.count
        ? h.start - 1 <= symbol.range.end.line &&
          h.start + h.count - 2 >= symbol.range.start.line
        : h.start >= symbol.range.start.line &&
          h.start <= symbol.range.end.line,
    );
  }
  private async compute(uri: vscode.Uri) {
    const meta = this.service.meta,
      file = this.service.git.relative(uri.fsPath),
      language = this.language(uri);
    const change = this.service.changes.find((c) => c.path === file);
    if (
      !meta ||
      !file ||
      !language ||
      !change ||
      change.status === "D" ||
      this.entries.has(file) ||
      this.pending.has(file)
    )
      return;
    this.pending.add(file);
    const generation = this.generation;
    const entries: ImpactEntry[] = [];
    try {
      const document = await vscode.workspace.openTextDocument(uri),
        symbols = await this.symbols(uri);
      if (!symbols) {
        entries.push({
          key: file,
          name: "",
          hash: "",
          uri,
          line: 0,
          kind: "",
          places: [],
          status: `Impact not available for ${language.name} in this IDE`,
        });
        return;
      }
      const hunks = await this.service.git.hunks(meta.range, change);
      // A base snapshot must have a declaration with the same qualified name. If its language provider cannot
      // analyse the snapshot, conservatively map current symbols only when their declaration maps to unchanged
      // base lines; changed headers require a base provider. This omits uncertain/new/renamed symbols.
      const baseUri = vscode.Uri.from({
        scheme: "review-lens",
        path: "/" + file,
        query: JSON.stringify({
          rev: meta.range.from,
          file: change.oldPath ?? file,
          empty: change.status === "A",
        }),
      });
      const baseText =
        change.status === "A"
          ? ""
          : await this.service.git.show(
              meta.range.from,
              change.oldPath ?? file,
            );
      const baseLines = baseText.split(/\r?\n/);
      let baseSymbols = await this.symbols(baseUri).catch(() => undefined);
      if (!baseSymbols && change.status !== "A") {
        // Symbol providers often accept only file: documents. A private, immutable base file gives the same
        // language extension syntax to analyse without changing the checkout or using textual name searches.
        const directory = path.join(
          this.service.git.gitDir,
          "review-lens",
          "impact-base",
          createHash("sha1")
            .update(meta.range.from + file)
            .digest("hex"),
        );
        const temporary = path.join(directory, path.basename(file));
        await fs.mkdir(directory, { recursive: true });
        try {
          await fs.writeFile(temporary, baseText);
          await vscode.workspace.openTextDocument(vscode.Uri.file(temporary));
          baseSymbols = await this.symbols(vscode.Uri.file(temporary));
        } finally {
          await fs.rm(temporary, { force: true });
          await fs.rmdir(directory).catch(() => {});
        }
      }
      for (const fn of symbols.filter(
        (s) => functions.has(s.symbol.kind) && this.inHunk(s.symbol, hunks),
      )) {
        if (change.status === "A" || change.status === "R") continue;
        const declaration = fn.symbol,
          header = document.lineAt(declaration.selectionRange.start.line).text;
        const oldSymbol = baseSymbols?.find(
          (s) => s.name === fn.name && s.symbol.kind === declaration.kind,
        );
        let baseLine: number | undefined;
        if (!oldSymbol) {
          // Map a line outside changed hunks back to its exact base position; no name search fallback.
          const line = declaration.selectionRange.start.line + 1;
          if (
            hunks.some(
              (h) => h.count && line >= h.start && line < h.start + h.count,
            )
          ) {
            if (
              !baseSymbols &&
              !entries.some((e) => e.status?.startsWith("Impact not"))
            )
              entries.push({
                key: file,
                name: "",
                hash: "",
                uri,
                line: 0,
                kind: "",
                places: [],
                status: `Impact not available for ${language.name} in this IDE`,
              });
            continue;
          }
          baseLine =
            line -
            hunks
              .filter((h) => h.start + h.count <= line)
              .reduce((n, h) => n + h.count - h.oldCount, 0) -
            1;
          if (baseLines[baseLine] !== header) continue;
        }
        const affected = hunks.filter((h) => this.inHunk(declaration, [h]));
        const before = affected
          .flatMap((h) =>
            baseLines.slice(h.oldStart - 1, h.oldStart - 1 + h.oldCount),
          )
          .join("\n");
        const after = affected
          .flatMap((h) =>
            document
              .getText()
              .split(/\r?\n/)
              .slice(h.start - 1, h.start - 1 + h.count),
          )
          .join("\n");
        if (meaningful(before) === meaningful(after)) continue;
        const text = document.getText(declaration.range),
          hash = createHash("sha1").update(text).digest("hex");
        let headerEnd = declaration.selectionRange.end.line;
        for (
          let line = declaration.selectionRange.start.line;
          line <=
          Math.min(
            declaration.range.end.line,
            declaration.selectionRange.start.line + 30,
          );
          line++
        ) {
          headerEnd = line;
          if (/[{:][ \t]*(?:\/\/.*)?$/.test(document.lineAt(line).text)) break;
        }
        const entry: ImpactEntry = {
          key: `${file}#${fn.name}`,
          name: fn.name,
          hash,
          uri,
          line: declaration.selectionRange.start.line,
          kind: affected.some((h) => h.start - 1 <= headerEnd)
            ? "inputs changed"
            : "logic changed",
          places: [],
        };
        this.service.impactKeys.add(entry.key);
        let timer: ReturnType<typeof setTimeout> | undefined;
        const deadline = Date.now() + 3000;
        const bounded = async <T>(work: PromiseLike<T>): Promise<T> => {
          try {
            return await Promise.race([
              work,
              new Promise<never>((_, reject) => {
                timer = setTimeout(
                  () => reject(new Error("impact-timeout")),
                  Math.max(1, deadline - Date.now()),
                );
              }),
            ]);
          } finally {
            clearTimeout(timer);
          }
        };
        try {
          const references = await bounded(
            vscode.commands.executeCommand<vscode.Location[]>(
              "vscode.executeReferenceProvider",
              uri,
              declaration.selectionRange.start,
            ),
          );
          if (!references) {
            entry.status = `Impact not available for ${language.name} in this IDE`;
            entries.push(entry);
            continue;
          }
          if (!references.length) {
            const definitions = await bounded(
              vscode.commands.executeCommand<vscode.Location[]>(
                "vscode.executeDefinitionProvider",
                uri,
                declaration.selectionRange.start,
              ),
            );
            if (!definitions?.length) {
              entry.status = `Impact not available for ${language.name} in this IDE`;
              entries.push(entry);
              continue;
            }
          }
          const places = new Map<string, ImpactPlace>();
          for (const ref of references) {
            if (generation !== this.generation) return;
            if (ref.uri.scheme !== "file") continue;
            const relative = this.service.git.relative(ref.uri.fsPath);
            if (
              !relative ||
              this.service.changes.some((c) => c.path === relative) ||
              /(^|\/)(node_modules|vendor|build|dist|generated|\.git)\//.test(
                relative,
              )
            )
              continue;
            const refDocument = await bounded(
                vscode.workspace.openTextDocument(ref.uri),
              ),
              line = refDocument.lineAt(ref.range.start.line).text;
            if (/^\s*(import\b|from\b|#include\b|require\b)/.test(line))
              continue;
            const containers = await bounded(this.symbols(ref.uri));
            const container = containers
              ?.filter(
                (s) =>
                  functions.has(s.symbol.kind) &&
                  s.symbol.range.contains(ref.range),
              )
              .sort(
                (a, b) =>
                  a.symbol.range.end.line -
                  a.symbol.range.start.line -
                  (b.symbol.range.end.line - b.symbol.range.start.line),
              )[0];
            const key = `${relative}#${container?.name ?? "<top level>"}`;
            if (!places.has(key))
              places.set(key, {
                key,
                path: relative,
                line: ref.range.start.line,
                range: ref.range,
                uri: ref.uri,
                test: isTestFile(relative),
                snippet:
                  line +
                  (ref.range.start.line + 1 < refDocument.lineCount
                    ? "\n" + refDocument.lineAt(ref.range.start.line + 1).text
                    : ""),
              });
          }
          entry.places = [...places.values()];
          if (entry.places.length) entries.push(entry);
        } catch (error) {
          if (error instanceof Error && error.message === "impact-timeout") {
            entry.status = "Many places · Open Find Usages";
            entries.push(entry);
          } else {
            entry.status = `Impact not available for ${language.name} in this IDE`;
            entries.push(entry);
            console.error("Review Lens impact search failed", error);
          }
        } finally {
          clearTimeout(timer);
        }
      }
    } catch (error) {
      entries.push({
        key: file,
        name: "",
        hash: "",
        uri,
        line: 0,
        kind: "",
        places: [],
        status: `Impact not available for ${language.name} in this IDE`,
      });
      console.error("Review Lens impact analysis failed", error);
    } finally {
      if (generation === this.generation) {
        this.entries.set(file, entries);
        this.pending.delete(file);
        this.changes.fire();
        if (
          entries.some((e) => e.status?.startsWith("Impact not")) &&
          (this.retries.get(file) ?? 0) < 3
        ) {
          this.retries.set(file, (this.retries.get(file) ?? 0) + 1);
          const timer = setTimeout(() => {
            this.timers.delete(timer);
            if (generation !== this.generation) return;
            this.entries.delete(file);
            void this.schedule();
          }, 3000);
          this.timers.add(timer);
        }
      }
    }
  }
  private checklist(entry: ImpactEntry) {
    return entry.places.filter(
      (p) => entry.places.filter((other) => other.test === p.test).length <= 5,
    );
  }
  private checked(entry: ImpactEntry, place: string) {
    const saved = this.service.meta?.checked?.[entry.key];
    return saved?.hash === entry.hash && saved.places.includes(place);
  }
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const file = this.service.git.relative(document.uri.fsPath);
    if (
      !file ||
      !this.service.meta ||
      !this.language(document.uri) ||
      !this.service.changes.some((c) => c.path === file)
    )
      return [];
    if (!this.entries.has(file)) {
      void this.compute(document.uri);
      return [
        new vscode.CodeLens(new vscode.Range(0, 0, 0, 0), {
          title: "Finding places…",
          command: "",
        }),
      ];
    }
    return this.entries.get(file)!.map((entry) => {
      const places = this.checklist(entry),
        done = places.length && places.every((p) => this.checked(entry, p.key));
      const dots = places
        .map((p) => (this.checked(entry, p.key) ? "●" : "○"))
        .join("");
      const title =
        entry.status ??
        (done
          ? `All ${places.length} places checked`
          : `${dots} ${entry.places.length} places use this · ${entry.kind} ⌄`);
      return new vscode.CodeLens(
        new vscode.Range(entry.line, 0, entry.line, 0),
        {
          title,
          command: entry.status?.startsWith("Impact not")
            ? ""
            : "reviewLens.impactPeek",
          arguments: [entry.key],
        },
      );
    });
  }
  private find(key: string) {
    return [...this.entries.values()].flat().find((e) => e.key === key);
  }
  private link(title: string, command: string, args: unknown[]) {
    return `[${title}](command:${command}?${encodeURIComponent(JSON.stringify(args))})`;
  }
  async peek(key: string, force = false) {
    const entry = this.find(key);
    if (!entry) return;
    const existing = this.cards.get(key);
    if (existing && !force) {
      existing.dispose();
      this.cards.delete(key);
      return;
    }
    existing?.dispose();
    if (entry.status)
      return vscode.commands.executeCommand(
        "editor.action.referenceSearch.trigger",
      );
    const checklist = this.checklist(entry),
      done = checklist.filter((p) => this.checked(entry, p.key)).length;
    const body = new vscode.MarkdownString(
      `**Outside this PR · ${done} of ${checklist.length} checked**\n\n`,
    );
    body.isTrusted = {
      enabledCommands: [
        "reviewLens.impactToggle",
        "reviewLens.impactOpen",
        "reviewLens.impactUsages",
      ],
    };
    for (const test of [false, true]) {
      const places = entry.places.filter((p) => p.test === test);
      if (!places.length) continue;
      body.appendMarkdown(`**${test ? "Tests" : "Code"}**\n\n`);
      if (places.length > 5) {
        body.appendMarkdown(
          `Widely used: ${places.length} ${test ? "tests" : "places"} · ${this.link("Open Find Usages", "reviewLens.impactUsages", [key])}\n\n`,
        );
        continue;
      }
      for (const place of places) {
        body.appendMarkdown(
          this.link(
            this.checked(entry, place.key) ? "☑" : "☐",
            "reviewLens.impactToggle",
            [key, place.key],
          ) + " ",
        );
        body.appendText(`${place.path}:${place.line + 1} `);
        body.appendMarkdown(
          this.link("↗", "reviewLens.impactOpen", [key, place.key]) + "\n",
        );
        body.appendCodeblock(place.snippet);
      }
    }
    if (
      !this.dynamicWarningShown &&
      ["Python", "JavaScript", "TypeScript", "Ruby", "PHP"].includes(
        this.language(entry.uri)?.name ?? "",
      )
    ) {
      body.appendText("Calls made dynamically may not show");
      this.dynamicWarningShown = true;
    }
    const thread = this.controller.createCommentThread(
      entry.uri,
      new vscode.Range(entry.line, 0, entry.line, 0),
      [{ body, mode: vscode.CommentMode.Preview, author: { name: "Impact" } }],
    );
    thread.canReply = false;
    thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    this.cards.set(key, thread);
  }
  async toggle(key: string, placeKey: string) {
    const entry = this.find(key);
    if (!entry || !this.checklist(entry).some((p) => p.key === placeKey))
      return;
    await this.service.updateChecked(key, entry.hash, placeKey);
    await this.peek(key, true);
  }
  async open(key: string, placeKey: string) {
    const place = this.find(key)?.places.find((p) => p.key === placeKey);
    if (place)
      await vscode.window.showTextDocument(place.uri, {
        selection: place.range,
        preview: false,
      });
  }
  async usages(key: string) {
    const entry = this.find(key);
    if (entry) {
      await vscode.window.showTextDocument(entry.uri, {
        selection: new vscode.Range(entry.line, 0, entry.line, 0),
      });
      await vscode.commands.executeCommand(
        "editor.action.referenceSearch.trigger",
      );
    }
  }
  async next() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) return;
    const file = this.service.git.relative(editor.document.uri.fsPath),
      entries = file ? (this.entries.get(file) ?? []) : [];
    const ordered = [
      ...entries
        .filter((e) => e.line <= editor.selection.active.line)
        .reverse(),
      ...entries.filter((e) => e.line > editor.selection.active.line),
    ];
    for (const entry of ordered) {
      const place = this.checklist(entry).find(
        (p) => !this.checked(entry, p.key),
      );
      if (place) {
        await this.peek(entry.key, true);
        await this.open(entry.key, place.key);
        return;
      }
    }
  }
  dispose() {
    this.generation++;
    this.timers.forEach((t) => clearTimeout(t));
    this.cards.forEach((c) => c.dispose());
    this.subscriptions.forEach((s) => s.dispose());
  }
}
