import React from "react";
import { AbsoluteFill, Easing, Img, interpolate, Sequence, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { Audio, Video } from "@remotion/media";
import { linearTiming, TransitionSeries } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { Caption, ease, FONT, Logo, Slides, Stage, TV, useRise } from "./parts";

const T = 15; // cross-fade between scenes, in frames

/** Opening: a Mauritius film, and the name. */
const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const a = (d: number) => interpolate(frame, [d, d + 0.7 * fps], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <AbsoluteFill style={{ scale: interpolate(frame, [0, 5 * fps], [1, 1.07]) }}>
        <Video src={staticFile("clips/le_morne.mp4")} muted style={{ width: "100%", height: "100%", objectFit: "cover" }} />
      </AbsoluteFill>
      <AbsoluteFill style={{ background: "radial-gradient(1100px 600px at 50% 52%, rgba(0,20,30,0.55), rgba(0,10,16,0.25) 70%)" }} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", color: "#fff", fontFamily: FONT }}>
        <div style={{ opacity: a(10), scale: interpolate(frame, [10, 40], [0.85, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }) }}>
          <Logo size={170} />
        </div>
        <div style={{ opacity: a(22), fontSize: 132, fontWeight: 800, letterSpacing: "-0.035em", marginTop: 34, textShadow: "0 8px 40px rgba(0,0,0,0.4)" }}>
          MoLakaz <span style={{ fontWeight: 300 }}>TV</span>
        </div>
        <div style={{ opacity: a(34) * 0.92, fontSize: 44, fontWeight: 500, marginTop: 8, textShadow: "0 4px 24px rgba(0,0,0,0.45)" }}>
          Your TV, at home.
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/** A screenshot (or several) on the TV, with a caption above. */
const OnTV: React.FC<{ kicker: string; title: string; sub?: string; srcs: string[]; each?: number }> = ({ kicker, title, sub, srcs, each = 60 }) => {
  const rise = useRise();
  return (
    <Stage>
      <Caption kicker={kicker} title={title} sub={sub} top={64} />
      <div style={{ position: "absolute", inset: 0, ...rise }}>
        <TV width={1340} top={sub ? 330 : 290}>
          <Slides srcs={srcs} each={each} />
        </TV>
      </div>
    </Stage>
  );
};

/** The films, full screen, with the place line the way Home shows it. */
const Films: React.FC = () => {
  const { fps } = useVideoConfig();
  const clips: [string, string][] = [
    ["reef", "Coral reef"],
    ["bacalar", "Bacalar Lagoon, Mexico"],
    ["halong_sunset", "Ha Long Bay at sunset, Vietnam"],
    ["jokulsarlon", "Jökulsárlón, Iceland"],
    ["fishing_boats_mu", "Fishing boats, Mauritius"],
  ];
  const each = Math.round(1.15 * fps);
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      {clips.map(([n, label], i) => (
        <Sequence key={n} from={i * each} durationInFrames={i === clips.length - 1 ? 3 * fps : each} layout="none">
          <AbsoluteFill>
            <Video src={staticFile(`clips/${n}.mp4`)} muted style={{ width: "100%", height: "100%", objectFit: "cover" }} />
            <div style={{ position: "absolute", left: 120, bottom: 96, color: "#fff", fontFamily: FONT, fontSize: 34, fontWeight: 600, textShadow: "0 3px 18px rgba(0,0,0,0.55)" }}>
              ⌖ {label}
            </div>
          </AbsoluteFill>
        </Sequence>
      ))}
      <AbsoluteFill style={{ background: "linear-gradient(180deg, rgba(0,12,20,0.55) 0%, rgba(0,12,20,0) 40%)" }} />
      <Caption kicker="LIVING BACKGROUND" title="27 nature films, always moving." sub="Four of them from Mauritius. Smoothed to 60 fps and cross-faded like a window." top={70} />
    </AbsoluteFill>
  );
};

/** The phone remote beside the TV showing its pairing code. */
const Phone: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const rise = useRise();
  return (
    <Stage>
      <Caption kicker="PHONE REMOTE" title="Your phone is the remote." sub="Scan the code once. It saves to the phone's home screen like an app." top={64} />
      <div style={{ position: "absolute", inset: 0, ...rise }}>
        <TV width={1180} top={340} left={150}>
          <Img src={staticFile("shots/settings_remote.jpg")} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
        </TV>
      </div>
      <div
        style={{
          position: "absolute", top: 300, left: 1400, width: 330, height: 716, borderRadius: 52, padding: 12,
          background: "linear-gradient(160deg, #2a3138, #0b0d10)", boxShadow: "0 50px 110px rgba(0,0,0,0.6)",
          translate: interpolate(frame, [0.3 * fps, 1.3 * fps], ["0px 900px", "0px 0px"], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }),
          rotate: interpolate(frame, [0.3 * fps, 1.3 * fps], ["8deg", "-3deg"], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }),
        }}
      >
        <Img src={staticFile("shots/remote.png")} style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: 42 }} />
      </div>
    </Stage>
  );
};

/** Close: the name, open source, where to get it. */
const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const a = (d: number) => interpolate(frame, [d, d + 0.7 * fps], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <AbsoluteFill style={{ scale: interpolate(frame, [0, 6 * fps], [1.04, 1]) }}>
        <Video src={staticFile("clips/lofoten.mp4")} muted style={{ width: "100%", height: "100%", objectFit: "cover" }} />
      </AbsoluteFill>
      <AbsoluteFill style={{ background: "linear-gradient(180deg, rgba(3,14,22,0.55), rgba(3,14,22,0.82))" }} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", color: "#fff", fontFamily: FONT, textAlign: "center" }}>
        <div style={{ opacity: a(4) }}><Logo size={140} /></div>
        <div style={{ opacity: a(12), fontSize: 110, fontWeight: 800, letterSpacing: "-0.035em", marginTop: 28 }}>
          MoLakaz <span style={{ fontWeight: 300 }}>TV</span>
        </div>
        <div style={{ opacity: a(22) * 0.92, fontSize: 40, fontWeight: 500, marginTop: 10 }}>Free and open source. For Android TV boxes.</div>
        <div style={{ opacity: a(34), marginTop: 46, fontSize: 36, fontWeight: 700, padding: "18px 34px", borderRadius: 999, background: "rgba(255,255,255,0.12)", border: "1px solid rgba(255,255,255,0.28)" }}>
          github.com/benosss/molakaz-tv
        </div>
        <div style={{ opacity: a(44) * 0.75, fontSize: 28, marginTop: 30, letterSpacing: "0.12em" }}>MADE IN MAURITIUS</div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/** The waves bed, faded at both ends. */
const Sound: React.FC = () => {
  const { durationInFrames, fps } = useVideoConfig();
  return (
    <Audio
      src={staticFile("waves.m4a")}
      volume={(f) => interpolate(f, [0, fps, durationInFrames - 2 * fps, durationInFrames], [0, 0.55, 0.55, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.inOut(Easing.quad) })}
    />
  );
};

export const PROMO_FRAMES = 120 + 150 + 165 + 180 + 135 + 150 + 120 + 135 + 165 - 8 * T;

export const Promo: React.FC = () => {
  const { fps } = useVideoConfig();
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <TransitionSeries>
        <TransitionSeries.Sequence durationInFrames={4 * fps}><Intro /></TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={5 * fps}>
          <OnTV kicker="A NEW HOME SCREEN" title="Calm, beautiful, and all yours." srcs={["shots/home_a.jpg", "shots/home_b.jpg"]} each={75} />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={5.5 * fps}><Films /></TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={6 * fps}>
          <OnTV kicker="PREVIEWS" title="Rest on a card. It starts playing." sub="YouTube videos, film trailers and Spotify songs, right behind your home screen."
            srcs={["shots/preview_youtube.jpg", "shots/preview_trailer.jpg", "shots/preview_spotify.jpg"]} each={60} />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={4.5 * fps}>
          <OnTV kicker="CONTINUE WATCHING" title="Pick up right where you left off." sub="Netflix, YouTube and Spotify in one row, plus fresh picks every hour."
            srcs={["shots/picks_tabs.jpg", "shots/nowplaying.jpg"]} each={68} />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={5 * fps}><Phone /></TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={4 * fps}>
          <OnTV kicker="GOODNIGHT" title="It fades out and falls asleep." sub="A sleep timer that dims any app, once or every night." srcs={["shots/goodnight.jpg"]} />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={4.5 * fps}>
          <OnTV kicker="SET-UP" title="From a USB stick. No computer." sub="Plug in, press OK, and it sets itself up." srcs={["shots/setup_welcome.jpg", "shots/setup_done.jpg"]} each={66} />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition presentation={fade()} timing={linearTiming({ durationInFrames: T })} />
        <TransitionSeries.Sequence durationInFrames={5.5 * fps}><Outro /></TransitionSeries.Sequence>
      </TransitionSeries>
      <Sound />
    </AbsoluteFill>
  );
};

/** README hero: the real screens, full bleed, dissolving into each other on a loop (no text). */
export const Hero: React.FC = () => {
  const srcs = ["shots/home_a.jpg", "shots/preview_spotify.jpg", "shots/home_b.jpg", "shots/preview_youtube.jpg", "shots/home_a.jpg"];
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <Slides srcs={srcs} each={66} fadeFrames={20} zoom={0} />
    </AbsoluteFill>
  );
};
