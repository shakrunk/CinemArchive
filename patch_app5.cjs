const fs = require('fs');
const content = fs.readFileSync('src/App.tsx', 'utf8');

const newContent = content.replace("import { useState, useEffect, useMemo } from 'react'", "import { useState, useEffect } from 'react'")
                          .replace("const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)\n", "");

fs.writeFileSync('src/App.tsx', newContent);
