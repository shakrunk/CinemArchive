/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{ts,tsx}",
  ],
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: {
        "2xl": "1400px",
      },
    },
    extend: {
      colors: {
        /* ── Cinematic palette (The Projection Room) ── */
        void: "#0b0907",
        ink: {
          DEFAULT: "#110d0b",
          1: "#17120f",
          2: "#1e1714",
          3: "#281f19",
        },
        amber: {
          DEFAULT: "#e9b266",
          bright: "#f7cd86",
          muted: "#c8924a",
          deep: "#c4863a",
          subtle: "#f5d799",
        },
        moon: "#8fb6cb",
        ember: {
          DEFAULT: "#d76a49",
          soft: "#e58e6f",
        },
        paper: {
          DEFAULT: "#f3ead9",
          dim: "#b7a994",
          faint: "#80735f",
        },

        /* ── shadcn semantic tokens (HSL vars) ── */
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
      },
      fontFamily: {
        serif: ["Fraunces", "Hoefler Text", "Georgia", "serif"],
        sans: ["Hanken Grotesk", "system-ui", "sans-serif"],
        mono: ["DM Mono", "ui-monospace", "monospace"],
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      boxShadow: {
        cinematic:
          "0 24px 60px -28px rgba(0,0,0,0.85), 0 4px 14px -8px rgba(0,0,0,0.6)",
        lift:
          "0 40px 90px -32px rgba(0,0,0,0.9), 0 10px 30px -12px rgba(0,0,0,0.7)",
        glow:
          "0 0 0 1px rgba(233,178,102,0.32), 0 18px 50px -18px rgba(233,178,102,0.28)",
      },
      transitionTimingFunction: {
        cine: "cubic-bezier(0.22, 1, 0.36, 1)",
        soft: "cubic-bezier(0.4, 0, 0.2, 1)",
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
        grain: {
          "0%": { backgroundPosition: "0 0" },
          "33%": { backgroundPosition: "-28px 12px" },
          "66%": { backgroundPosition: "22px -18px" },
          "100%": { backgroundPosition: "-12px 16px" },
        },
        flicker: {
          "0%,100%": { opacity: "0.9" },
          "48%": { opacity: "0.72" },
          "50%": { opacity: "1" },
          "52%": { opacity: "0.8" },
          "70%": { opacity: "0.95" },
        },
        drift: {
          from: { transform: "translateY(0)" },
          to: { transform: "translateY(-40px)" },
        },
        "spin-slow": {
          to: { transform: "rotate(360deg)" },
        },
        blink: {
          "50%": { opacity: "0.3" },
        },
        "view-in": {
          from: { opacity: "0", transform: "translateY(14px)" },
          to: { opacity: "1", transform: "none" },
        },
        "poster-in": {
          to: { opacity: "1", transform: "none" },
        },
        "bar-grow": {
          to: { transform: "scaleX(1)" },
        },
        "col-grow": {
          to: { transform: "scaleY(1)" },
        },
        shimmer: {
          to: { backgroundPosition: "-200% 0" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
        grain: "grain 0.6s steps(3) infinite",
        flicker: "flicker 7s ease-in-out infinite",
        drift: "drift 26s linear infinite",
        "spin-slow": "spin-slow 24s linear infinite",
        blink: "blink 2s ease-in-out infinite",
        "view-in": "view-in 0.6s cubic-bezier(0.22,1,0.36,1) both",
        "poster-in": "poster-in 0.6s cubic-bezier(0.22,1,0.36,1) forwards",
        shimmer: "shimmer 1.4s linear infinite",
      },
    },
  },
  plugins: [],
}
