import tailwindcss from 'tailwindcss'
import autoprefixer from 'autoprefixer'
import textScale from './scripts/postcss-text-scale.js'

export default {
  plugins: [tailwindcss(), autoprefixer(), textScale()],
}
