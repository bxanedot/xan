import { Footer } from "@/components/layout/Footer";
import { Navbar } from "@/components/layout/Navbar";
import { BackToTop } from "@/components/layout/BackToTop";
import { ScrollProgress } from "@/components/layout/ScrollProgress";
import { Editorial } from "@/components/sections/Editorial";
import { EqualizerSpotlight } from "@/components/sections/EqualizerSpotlight";
import { FeatureSection } from "@/components/sections/FeatureSection";
import { Hero } from "@/components/sections/Hero";
import { SupportCommunity } from "@/components/sections/SupportCommunity";
import { ScreenshotShowcase } from "@/components/sections/ScreenshotShowcase";
import { VisualSection } from "@/components/sections/VisualSection";

export default function Home() {
  return (
    <main className="overflow-hidden">
      <ScrollProgress />
      <Navbar />
      <Hero />
      <EqualizerSpotlight />
      <ScreenshotShowcase />
      <FeatureSection />
      <div className="feature-pair">
        <Editorial />
        <VisualSection />
      </div>
      <SupportCommunity />
      <Footer />
      <BackToTop />
    </main>
  );
}
