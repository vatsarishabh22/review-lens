/** Ignore formatting and comments, but preserve literal content, including spaces in strings. */
export function meaningful(text: string): string {
  return text.replace(
    /("""[\s\S]*?"""|'''[\s\S]*?'''|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(\/\*[\s\S]*?\*\/|\/\/[^\r\n]*|^[ \t]*#[^\r\n]*|\s+)/gm,
    (_match, literal) => literal ?? "",
  );
}
