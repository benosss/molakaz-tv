# The promo film

The 40-second film and the README's hero animation, made with [Remotion](https://www.remotion.dev).
Every screen in it is a real screenshot from the box; the nature clips are cut from the app's own films.

```bash
npm i
npx remotion studio                 # preview and edit
npx remotion render Promo out/molakaz-tv-film.mp4
npx remotion render Hero out/hero.mp4 --scale=0.5   # then: ffmpeg … -c:v libwebp_anim hero.webp
```

`public/` (screenshots, clips, the waves sound) isn't in the repo: put the screenshots in `public/shots/`, short clips
of the films in `public/clips/`, and `icon.svg` from `docs/assets/`.
