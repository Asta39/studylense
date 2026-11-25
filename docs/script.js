// Reveal sections as they scroll into view.
const io = new IntersectionObserver(
  (entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        e.target.classList.add('in');
        io.unobserve(e.target);
      }
    }
  },
  { threshold: 0.12, rootMargin: '0px 0px -40px 0px' },
);
document.querySelectorAll('.reveal').forEach((el) => io.observe(el));

// Point the main button at the visitor's platform.
const base = 'https://github.com/Asta39/studylense/releases/latest/download/';
const ua = navigator.userAgent;
const os = /Android/i.test(ua) ? 'android' : /Mac/i.test(ua) && !/iPhone|iPad/i.test(ua) ? 'mac' : /Win/i.test(ua) ? 'windows' : null;
if (os) {
  document.querySelector(`.platform[data-os="${os}"]`)?.classList.add('mine');
  const hero = document.getElementById('hero-download');
  const files = {
    android: ['Download for Android', 'StudyLens-Android.apk'],
    mac: ['Download for Mac', 'StudyLens-macOS.zip'],
    windows: ['Download for Windows', 'StudyLens-Windows-x64.exe'],
  };
  const [label, file] = files[os];
  hero.textContent = label;
  hero.href = base + file;
}
