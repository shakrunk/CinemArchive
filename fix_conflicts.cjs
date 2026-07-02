const fs = require('fs');
let content = fs.readFileSync('src/views/Discover.tsx', 'utf8');

content = content.replace(/<<<<<<< HEAD\n\s*className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto"\n=======\n\s*className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"\n>>>>>>> origin\/main/g,
  'className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto"');

fs.writeFileSync('src/views/Discover.tsx', content);
