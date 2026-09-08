/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        octo: {
          bg: "#0A0D14",
          surface: "#111520",
          elevated: "#181E2E",
          border: "#232A3E",
          primary: "#007AFF",
          success: "#34C759",
          warning: "#FF9500",
          danger: "#FF3B30",
          textSecondary: "#8A94A6"
        }
      }
    },
  },
  plugins: [],
}
