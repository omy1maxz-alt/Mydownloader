const fs = require('fs');
const content = fs.readFileSync('app/src/main/assets/removemark_doubao.js', 'utf8');

// The syntax error reported by Android WebView: "Uncaught SyntaxError: Unexpected end of input"
// This usually means `evaluateJavascript` received an incomplete string or there's an unterminated literal.

// Let's verify if JS engine can parse it as an IIFE
try {
  new Function(content);
  console.log("No syntax errors found in pure JS execution");
} catch(e) {
  console.error("Syntax Error Found:", e);
}
