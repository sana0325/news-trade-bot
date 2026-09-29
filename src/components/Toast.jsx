import { useEffect, useRef } from 'react';

export default function Toast({ text, onDone }) {
  const done = useRef(onDone);
  done.current = onDone;
  useEffect(() => {
    const t = setTimeout(() => done.current(), 4000);
    return () => clearTimeout(t);
  }, []);
  return (
    <div className="toast" role="status">
      {text}
    </div>
  );
}
