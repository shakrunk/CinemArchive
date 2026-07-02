#!/bin/bash
sed -i -e '/<<<<<<< HEAD/d' -e '/=======/d' -e '/>>>>>>> origin\/main/d' src/views/Discover.tsx
sed -i -e 's/className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto"//g' src/views/Discover.tsx
sed -i -e 's/className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"//g' src/views/Discover.tsx
