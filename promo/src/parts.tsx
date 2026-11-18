import React from "react";
import { AbsoluteFill, Easing, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig } from "remotion";

export const FONT = '-apple-system, "SF Pro Display", "Helvetica Neue", Arial, sans-serif';
export const ease = Easing.bezier(0.16, 1, 0.3, 1);

/** The room the TV sits in: deep lagoon blue, a soft light from the top. */
export const Stage: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <AbsoluteFill
    style={{
      background:
        "radial-gradient(1200px 700px at 50% -10%, rgba(25,181,196,0.32), transparent 70%), radial-gradient(900px 600px at 85% 110%, rgba(59,168,240,0.18), transparent 70%), linear-gradient(180deg, #071823 0%, #04101A 100%)",
      fontFamily: FONT,
    }}
  >
    {children}
  </AbsoluteFill>
);

/** A thin-bezel TV with a soft reflection. The screenshot inside is exactly what the box shows. */
export const TV: React.FC<{ width: number; top: number; left?: number; children: React.ReactNode }> = ({ width, top, left, children }) => {
  const h = (width * 9) / 16;
  return (
    <div
      style={{
        position: "absolute",
        width,
        height: h,
        top,
        left: left ?? (1920 - width) / 2,
        borderRadius: 18,
        padding: 12,
        background: "linear-gradient(160deg, #1b2229, #07090b)",
        boxShadow: "0 50px 120px rgba(0,0,0,0.55), 0 0 0 1px rgba(255,255,255,0.08) inset",
      }}
    >
      <div style={{ position: "relative", width: "100%", height: "100%", borderRadius: 8, overflow: "hidden", background: "#000" }}>
        {children}
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(125deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0) 38%)" }} />
      </div>
    </div>
  );
};

/** Screenshots cross-dissolving one into the next, each holding for [each] frames. */
export const Slides: React.FC<{ srcs: string[]; each: number; fadeFrames?: number; zoom?: number }> = ({ srcs, each, fadeFrames = 14, zoom = 0.04 }) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill>
      {srcs.map((s, i) => {
        const start = i * each;
        const opacity = i === 0 ? 1 : interpolate(frame, [start - fadeFrames / 2, start + fadeFrames / 2], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
        return (
          <AbsoluteFill key={s} style={{ opacity }}>
            <Img
              src={staticFile(s)}
              style={{
                width: "100%",
                height: "100%",
                objectFit: "cover",
                scale: interpolate(frame, [start - fadeFrames, start + each + fadeFrames], [1, 1 + zoom], { extrapolateLeft: "clamp", extrapolateRight: "clamp" }),
              }}
            />
          </AbsoluteFill>
        );
      })}
    </AbsoluteFill>
  );
};

/** Kicker + headline + supporting line, rising in. */
export const Caption: React.FC<{ kicker: string; title: string; sub?: string; top?: number; align?: "left" | "center"; delay?: number; width?: number }> = ({
  kicker, title, sub, top = 90, align = "center", delay = 6, width = 1500,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = (d: number) => ({
    opacity: interpolate(frame, [delay + d, delay + d + 0.6 * fps], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }),
    translate: interpolate(frame, [delay + d, delay + d + 0.8 * fps], ["0px 28px", "0px 0px"], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }),
  });
  return (
    <div style={{ position: "absolute", top, left: align === "center" ? (1920 - width) / 2 : 120, width, textAlign: align, color: "#fff", fontFamily: FONT }}>
      <div style={{ ...t(0), fontSize: 26, fontWeight: 700, letterSpacing: "0.18em", color: "#7FE3EE" }}>{kicker}</div>
      <div style={{ ...t(4), fontSize: 72, fontWeight: 800, letterSpacing: "-0.025em", marginTop: 14, lineHeight: 1.05 }}>{title}</div>
      {sub ? <div style={{ ...t(9), fontSize: 34, fontWeight: 400, opacity: 0.82, marginTop: 18, lineHeight: 1.35 }}>{sub}</div> : null}
    </div>
  );
};

/** The TV's scale-in: from slightly small and low to rest. */
export const useRise = (from = 0.94) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return {
    scale: interpolate(frame, [0, 1.1 * fps], [from, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }),
    translate: interpolate(frame, [0, 1.1 * fps], ["0px 40px", "0px 0px"], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease }),
  };
};

export const Logo: React.FC<{ size: number }> = ({ size }) => (
  <Img src={staticFile("icon.svg")} style={{ width: size, height: size, borderRadius: size * 0.23, boxShadow: "0 24px 60px rgba(0,30,50,0.45)" }} />
);
