const fs = require('fs');
const content = fs.readFileSync('src/App.tsx', 'utf8');
const lines = content.split('\n');

const newLines = [];
let i = 0;
while (i < lines.length) {
  const line = lines[i];
  if (line.includes('// Build the command list + an id→handler map. Title commands open the drawer')) {
    i += 2; // skip this and the next line
    continue;
  }
  newLines.push(line);
  i++;
}

fs.writeFileSync('src/App.tsx', newLines.join('\n'));
