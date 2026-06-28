const fs = require('fs');

let appContent = fs.readFileSync('src/App.tsx', 'utf8');
appContent = appContent.replace("  const openAddTitle = useAppStore((s) => s.openAddTitle)\n  const setViewMode = useAppStore((s) => s.setViewMode)\n", "");
fs.writeFileSync('src/App.tsx', appContent);

let cpContent = fs.readFileSync('src/components/CommandPalette.tsx', 'utf8');
cpContent = cpContent.replace(
  "  const openAddTitle = useAppStore.getState().openAddTitle\n  const openDetailDrawer = useAppStore.getState().openDetailDrawer\n  const setViewMode = useAppStore.getState().setViewMode\n",
  "  const openAddTitle = useAppStore((s) => s.openAddTitle)\n  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)\n  const setViewMode = useAppStore((s) => s.setViewMode)\n"
);
fs.writeFileSync('src/components/CommandPalette.tsx', cpContent);
