import { Composition } from "remotion";
import { Hero, Promo, PROMO_FRAMES } from "./Promo";

export const RemotionRoot: React.FC = () => {
  return (
    <>
      {/* The 40-second film for the README, socials and the website. */}
      <Composition id="Promo" component={Promo} durationInFrames={PROMO_FRAMES} fps={30} width={1920} height={1080} />
      {/* The looping animation at the top of the README (rendered small, as animated WebP). */}
      <Composition id="Hero" component={Hero} durationInFrames={4 * 66 + 10} fps={30} width={1920} height={1080} />
    </>
  );
};
