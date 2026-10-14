import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { createRequire } from "node:module";
import { createElement as h } from "react";

const require = createRequire(import.meta.url);
const { ImageResponse } = require("next/og");

const root = process.cwd();
const logo = await readFile(join(root, "public", "xan-white-mark-512.png"));
const logoData = `data:image/png;base64,${logo.toString("base64")}`;
const waveform = [24, 38, 60, 44, 76, 54, 90, 64, 106, 48, 78, 118, 66, 96, 48, 84, 110, 58, 76, 42, 92, 56, 72, 34, 52, 26];

const image = new ImageResponse(
  h("div", {
    style: {
      display: "flex",
      width: "100%",
      height: "100%",
      position: "relative",
      overflow: "hidden",
      background: "linear-gradient(130deg, #121212 0%, #080808 72%)",
      color: "#f7f7f7",
      fontFamily: "Arial, sans-serif",
    },
    children: [
      h("div", {
        style: {
          display: "flex",
          width: 430,
          height: "100%",
          alignItems: "center",
          justifyContent: "center",
          background: "linear-gradient(145deg, #2a0d14 0%, #150b0d 52%, #0b0b0b 100%)",
          borderRight: "1px solid #fa2d482e",
          position: "relative",
        },
        children: [
          h("div", { style: { position: "absolute", width: 390, height: 390, borderRadius: 999, border: "1px solid #fa2d482e", top: 120, left: 20 } }),
          h("div", { style: { position: "absolute", width: 310, height: 310, borderRadius: 999, border: "1px solid #fa2d4820", top: 160, left: 60 } }),
          h("img", { src: logoData, width: 270, height: 270, style: { objectFit: "contain", position: "relative" } }),
        ],
      }),
      h("div", {
        style: {
          display: "flex",
          flexDirection: "column",
          flex: 1,
          justifyContent: "center",
          padding: "64px 74px",
          position: "relative",
        },
        children: [
          h("div", { style: { display: "flex", alignItems: "center", gap: 14, marginBottom: 32 } }, [
            h("span", { style: { color: "#fa2d48", fontSize: 18, fontWeight: 700, letterSpacing: 6 } }, "XAN"),
            h("span", { style: { width: 48, height: 1, background: "#fa2d4888" } }),
            h("span", { style: { color: "#d3c5c8", fontSize: 15, letterSpacing: 2 } }, "FREE · OPEN-SOURCE PLAYER"),
          ]),
          h("div", { style: { display: "flex", flexDirection: "column", fontSize: 68, fontWeight: 700, lineHeight: 1.04, letterSpacing: -3.5, maxWidth: 660 } }, [
            h("span", {}, "Free to use."),
            h("span", { style: { color: "#fa2d48", fontSize: 46, letterSpacing: -1.5 } }, "Made for the love of music."),
          ]),
          h("div", { style: { color: "#c8c4c5", fontSize: 23, lineHeight: 1.5, marginTop: 24, maxWidth: 570 } }, "Keep your library, discover artists, and download available catalogues in the app."),
          h("div", { style: { display: "flex", alignItems: "center", gap: 7, height: 108, marginTop: 24, opacity: 0.9 } }, waveform.map((height, index) => h("span", { key: index, style: { width: 7, height: Math.round(height * 0.82), borderRadius: 7, background: index % 4 === 0 ? "#ffffff" : "#fa2d48" } }))),
          h("div", { style: { position: "absolute", right: 74, bottom: 58, color: "#fa2d48", fontSize: 22, letterSpacing: 2 } }, "XAN.LAT"),
        ],
      }),
      h("div", { style: { position: "absolute", top: 0, right: 0, width: 8, height: "100%", background: "linear-gradient(180deg, #ff536a, #c91c3c)" } }),
    ],
  }),
  { width: 1200, height: 630 },
);

await writeFile(join(root, "public", "xan-social-card.png"), Buffer.from(await image.arrayBuffer()));
