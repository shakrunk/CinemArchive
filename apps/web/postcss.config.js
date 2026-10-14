import textScale from './scripts/postcss-text-scale.js'

// Tailwind itself runs through @tailwindcss/vite (see vite.config.ts); this
// pipeline only hosts the text-scale pass over the generated CSS.
export default {
  plugins: [textScale()],
}
