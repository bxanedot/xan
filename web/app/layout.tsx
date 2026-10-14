import type { Metadata, Viewport } from "next";
import { LanguageProvider } from "@/lib/i18n";
import "./globals.css";

const logo = "/xan-white-mark-512.png";
const socialImage = "/xan-social-card.png";
const socialTitle = "XAN — Free to use. Made for the love of music.";

const themeScript = `
(function () {
  try {
    var saved = localStorage.getItem("xan-theme");
    var theme = saved === "gold" ? "gold" : "dark";
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = "dark";
  } catch (error) {
    document.documentElement.dataset.theme = "dark";
    document.documentElement.style.colorScheme = "dark";
  }
})();
`;

const languageScript = `
(function () {
  try {
    var saved = localStorage.getItem("xan-language");
    var supported = ["en", "de", "es", "ar", "fa", "fr"];
    var language = supported.indexOf(saved) >= 0 ? saved : "en";
    document.documentElement.lang = language;
    document.documentElement.dir = language === "ar" || language === "fa" ? "rtl" : "ltr";
    document.documentElement.dataset.language = language;
  } catch (error) {}
})();
`;

export const metadata: Metadata = {
  metadataBase: new URL("https://xan.lat"),
  alternates: {
    canonical: "/",
  },
  title: socialTitle,
  description: "A free, open-source Android player. Discover artists and download available catalogues.",
  applicationName: "XAN",
  icons: {
    icon: logo,
    shortcut: logo,
    apple: logo,
  },
  openGraph: {
    url: "https://xan.lat/",
    siteName: "XAN",
    locale: "en_US",
    title: socialTitle,
    description: "A free, open-source Android player. Discover artists and download available catalogues.",
    type: "website",
    images: [{ url: socialImage, width: 1200, height: 630, alt: socialTitle, type: "image/png" }],
  },
  twitter: {
    card: "summary_large_image",
    site: "@bxanedot",
    creator: "@bxanedot",
    title: socialTitle,
    description: "A free, open-source Android player. Discover artists and download available catalogues.",
    images: [socialImage],
  },
};

export const viewport: Viewport = {
  themeColor: "#050505",
  colorScheme: "dark",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" data-theme="dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript + languageScript }} />
      </head>
      <body><LanguageProvider>{children}</LanguageProvider></body>
    </html>
  );
}
