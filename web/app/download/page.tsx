import type { Metadata } from "next";
import { PlatformDownloads } from "@/components/sections/PlatformDownloads";

export const metadata: Metadata = {
  title: "Download XAN",
  description: "Choose the XAN installer for your device.",
};

export default function DownloadPage() {
  return <PlatformDownloads />;
}
