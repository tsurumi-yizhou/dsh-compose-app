// Code is passed as a string to the lexer. It is never evaluated as JavaScript.
hljs.registerLanguage("vue", function (lexer) {
  const xml = lexer.getLanguage("xml");
  return lexer.inherit(xml, {
    name: "Vue",
    aliases: [],
    contains: [{ begin: /\{\{/, end: /\}\}/, subLanguage: "javascript", excludeBegin: true, excludeEnd: true }, ...xml.contains]
  });
});
globalThis.syntaxTokens = function (code, language) {
  if (!hljs.getLanguage(language)) return "[]";
  const result = hljs.highlight(code, { language, ignoreIllegals: true });
  const tokens = [];
  let offset = 0;
  function walk(node, inherited) {
    if (typeof node === "string") {
      const end = offset + node.length;
      if (inherited && end > offset) {
        const previous = tokens[tokens.length - 1];
        if (previous && previous[1] === offset && previous[2] === inherited) previous[1] = end;
        else tokens.push([offset, end, inherited]);
      }
      offset = end;
    } else {
      for (const child of node.children) walk(child, node.scope || inherited);
    }
  }
  walk(result._emitter.rootNode, "");
  return JSON.stringify(tokens);
};
