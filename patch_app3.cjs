const fs = require('fs');
const content = fs.readFileSync('src/App.tsx', 'utf8');
const lines = content.split('\n');

const newLines = [];
let i = 0;
while (i < lines.length) {
  const line = lines[i];
  if (line.includes('useEffect(() => {') && lines[i+1]?.includes('if (!isSupabaseConfigured) return')) {
     newLines.push(line);
     i++;
     continue;
  }
  newLines.push(line);
  i++;
}

fs.writeFileSync('src/App.tsx', newLines.join('\n'));
