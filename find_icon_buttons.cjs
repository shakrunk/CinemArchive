const fs = require('fs');
const path = require('path');

function processFile(filePath) {
  const content = fs.readFileSync(filePath, 'utf-8');
  let inButton = false;
  let buttonContent = '';
  let buttonStartLine = 0;

  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.includes('<button')) {
      inButton = true;
      buttonStartLine = i + 1;
      buttonContent = line;
      if (line.includes('</button>')) {
        inButton = false;
        checkButton(buttonContent, filePath, buttonStartLine);
        buttonContent = '';
      }
    } else if (inButton) {
      buttonContent += '\n' + line;
      if (line.includes('</button>')) {
        inButton = false;
        checkButton(buttonContent, filePath, buttonStartLine);
        buttonContent = '';
      }
    }
  }
}

function checkButton(content, filePath, line) {
  // If it has aria-label, it's fine
  if (content.includes('aria-label=')) return;

  // Strip tags to get text content
  const textContent = content.replace(/<[^>]+>/g, '').trim();

  // If there is no text content and it only contains an Icon component or similar
  if (textContent.length === 0 || /^\{[\s\S]*\}$/.test(textContent) || textContent === '...') {
    console.log(`${filePath}:${line}`);
    console.log(content);
    console.log('---');
  }
}

function walkDir(dir) {
  const files = fs.readdirSync(dir);
  for (const file of files) {
    const filePath = path.join(dir, file);
    const stat = fs.statSync(filePath);
    if (stat.isDirectory()) {
      walkDir(filePath);
    } else if (filePath.endsWith('.tsx') || filePath.endsWith('.jsx')) {
      processFile(filePath);
    }
  }
}

walkDir('src');
