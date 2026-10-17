import * as vscode from "vscode";

// Skeleton: the command exists so the extension installs and runs; the review flow is spec/README.md, built here next.
export function activate(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand("reviewLens.reviewPr", () =>
      vscode.window.showInformationMessage("Review Lens for VS Code is coming soon. It's available today for JetBrains IDEs."),
    ),
  );
}

export function deactivate() {}
