const fs = require('fs');
const content = fs.readFileSync('src/App.tsx', 'utf8');
const lines = content.split('\n');

const newLines = [];
let skipMode = false;

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];

  if (line.includes('const titles = useAppStore((s) => s.titles)')) {
    continue; // Skip this line
  }

  if (line.includes('const { commands, runMap } = useMemo(() => {')) {
    skipMode = true;
    continue;
  }

  if (skipMode) {
    if (line.includes('}, [titles, isSharedView, openAddTitle, openDetailDrawer, setViewMode])')) {
      skipMode = false;
    }
    continue;
  }

  if (line.includes('function runCommand(cmd: Command) {')) {
    skipMode = true;
    continue;
  }

  if (skipMode && line.includes('runMap[cmd.id]?.()')) {
    continue; // Wait for the closing bracket
  }

  if (skipMode && line.includes('}')) {
    skipMode = false;
    continue;
  }

  if (line.includes('commands={commands}')) {
    newLines.push(line.replace('commands={commands}', 'onNavigate={setCurrentView}'));
    continue;
  }

  if (line.includes('onRun={runCommand}')) {
    continue; // Remove onRun
  }

  if (line.includes('import type { Command } from \'src/store/commands\'')) {
      continue;
  }

  newLines.push(line);
}

fs.writeFileSync('src/App.tsx', newLines.join('\n'));
