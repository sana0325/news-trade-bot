import { Capacitor, registerPlugin } from '@capacitor/core';

// Файли з «Відкрити за допомогою» / «Поділитися» (див. IncomingFilePlugin.java).
const IncomingFile = registerPlugin('IncomingFile');

function toBuffer(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

export function listenIncoming(onFile, onError) {
  if (!Capacitor.isNativePlatform()) return () => {};
  const take = async () => {
    const res = await IncomingFile.takePending();
    if (res.error) onError(res.error);
    else if (res.data) onFile({ name: res.name, data: toBuffer(res.data) });
  };
  take().catch((e) => onError(e.message));
  const sub = IncomingFile.addListener('file', () => take().catch((e) => onError(e.message)));
  return () => sub.then((s) => s.remove());
}
