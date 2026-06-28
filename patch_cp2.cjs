const fs = require('fs');
const content = fs.readFileSync('src/components/CommandPalette.tsx', 'utf8');

const newContent = content.replace("  const openAddTitle = useAppStore((s) => s.openAddTitle)\n  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)\n  const setViewMode = useAppStore((s) => s.setViewMode)",
`  const openAddTitle = useAppStore.getState().openAddTitle
  const openDetailDrawer = useAppStore.getState().openDetailDrawer
  const setViewMode = useAppStore.getState().setViewMode`
);

fs.writeFileSync('src/components/CommandPalette.tsx', newContent);
