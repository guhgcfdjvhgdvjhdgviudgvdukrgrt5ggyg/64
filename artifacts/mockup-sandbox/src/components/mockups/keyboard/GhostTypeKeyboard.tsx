export function GhostTypeKeyboard() {
  const rows = [
    ["Q","W","E","R","T","Y","U","I","O","P"],
    ["A","S","D","F","G","H","J","K","L"],
    ["Z","X","C","V","B","N","M"],
  ];

  const keyEmojis: Record<string, string> = {
    E: "✨", T: "❤️", W: "⭐", J: "🌈", L: "🌈",
    D: "💛", Z: "🌈", B: "🌈", N: "🌈",
  };

  const numRow = ["1","2","3","4","5","6","7","8","9","0"];
  const numEmojis: Record<string, string> = {
    "3": "⭐", "5": "❤️",
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "flex-end",
        justifyContent: "center",
        background: "linear-gradient(180deg, #e8f4fd 0%, #daeef9 100%)",
        fontFamily: "'Segoe UI', Arial, sans-serif",
        padding: "0 0 16px 0",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 420,
          background: "linear-gradient(180deg, #c8e6f7 0%, #b8ddf0 100%)",
          borderRadius: "16px 16px 0 0",
          padding: "8px 4px 12px 4px",
          boxShadow: "0 -4px 24px rgba(100,160,210,0.18)",
        }}
      >
        {/* Suggestion bar */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            background: "rgba(210,235,250,0.7)",
            borderRadius: 12,
            marginBottom: 6,
            padding: "4px 10px",
            gap: 8,
            height: 38,
          }}
        >
          <span style={{ fontSize: 18, color: "#7ab0d0", marginRight: 4 }}>☰</span>
          <span style={{ flex: 1, fontSize: 13, color: "#7ab0d0", textAlign: "center" }}>·</span>
          <span style={{ fontSize: 18, color: "#7ab0d0" }}>🔍</span>
        </div>

        {/* Number row */}
        <div style={{ display: "flex", justifyContent: "center", gap: 3, marginBottom: 5 }}>
          {numRow.map((num) => (
            <Key key={num} label={num} emoji={numEmojis[num]} small />
          ))}
        </div>

        {/* QWERTY rows */}
        {rows.map((row, ri) => (
          <div key={ri} style={{ display: "flex", justifyContent: "center", gap: 4, marginBottom: 5 }}>
            {ri === 2 && <SpecialKey label="⬆" emoji="❤️" wide />}
            {row.map((k) => (
              <Key key={k} label={k} emoji={keyEmojis[k]} />
            ))}
            {ri === 2 && <SpecialKey label="⭐✕" emoji="" wide isDelete />}
          </div>
        ))}

        {/* Bottom row */}
        <div style={{ display: "flex", justifyContent: "center", gap: 4, marginTop: 2 }}>
          <SpecialKey label="?123" emoji="🌈" wide />
          <SpecialKey label="😊" emoji="" />
          <SpecialKey label="🎤" emoji="🌿" />
          {/* Space bar */}
          <div
            style={{
              flex: 1,
              background: "linear-gradient(180deg, #e8f4fc 0%, #d0e9f6 100%)",
              borderRadius: 14,
              height: 48,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              position: "relative",
              boxShadow: "0 3px 0 #a8c8e0, 0 4px 8px rgba(100,160,200,0.15)",
              border: "1.5px solid rgba(255,255,255,0.9)",
            }}
          >
            <span style={{ fontSize: 12, color: "#5590b0", fontWeight: 500 }}>English</span>
            {/* emoji decorations on space bar */}
            <span style={{ position: "absolute", left: 18, top: 4, fontSize: 13 }}>⭐</span>
            <span style={{ position: "absolute", left: 38, top: 22, fontSize: 11 }}>🌿</span>
            <span style={{ position: "absolute", right: 36, top: 6, fontSize: 11 }}>🌿</span>
            <span style={{ position: "absolute", right: 18, top: 22, fontSize: 12 }}>❤️</span>
          </div>
          <SpecialKey label="🌐" emoji="" />
          <SpecialKey label="!?" emoji="" />
          <SpecialKey label="⏎" emoji="🌈" wide />
        </div>
      </div>
    </div>
  );
}

function Key({ label, emoji, small }: { label: string; emoji?: string; small?: boolean }) {
  return (
    <div
      style={{
        position: "relative",
        width: small ? 30 : 34,
        height: small ? 36 : 50,
        background: "linear-gradient(180deg, #f4faff 0%, #e2f2fc 100%)",
        borderRadius: small ? 8 : 12,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        boxShadow: "0 3px 0 #a8c8e0, 0 4px 8px rgba(100,160,200,0.12)",
        border: "1.5px solid rgba(255,255,255,0.95)",
        flexShrink: 0,
        cursor: "default",
      }}
    >
      {emoji && (
        <span
          style={{
            position: "absolute",
            top: small ? 1 : 2,
            right: small ? 2 : 3,
            fontSize: small ? 8 : 10,
            lineHeight: 1,
          }}
        >
          {emoji}
        </span>
      )}
      <span
        style={{
          fontSize: small ? 12 : 22,
          fontWeight: 700,
          color: "#3a6a8a",
          lineHeight: 1,
          marginTop: emoji ? 2 : 0,
        }}
      >
        {label}
      </span>
    </div>
  );
}

function SpecialKey({
  label,
  emoji,
  wide,
  isDelete,
}: {
  label: string;
  emoji?: string;
  wide?: boolean;
  isDelete?: boolean;
}) {
  return (
    <div
      style={{
        position: "relative",
        width: wide ? 50 : 36,
        height: 50,
        background: isDelete
          ? "linear-gradient(180deg, #f0f8ff 0%, #d8edf8 100%)"
          : "linear-gradient(180deg, #e0f0fa 0%, #cce5f5 100%)",
        borderRadius: 12,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        boxShadow: "0 3px 0 #98b8d0, 0 4px 8px rgba(100,160,200,0.12)",
        border: "1.5px solid rgba(255,255,255,0.85)",
        flexShrink: 0,
        cursor: "default",
        overflow: "hidden",
      }}
    >
      {/* emoji decoration bg for some special keys */}
      {emoji && (
        <span
          style={{
            position: "absolute",
            bottom: 2,
            right: 3,
            fontSize: 14,
            opacity: 0.85,
          }}
        >
          {emoji}
        </span>
      )}
      <span style={{ fontSize: isDelete ? 13 : 11, color: "#4a7a9a", fontWeight: 600, zIndex: 1 }}>
        {isDelete ? "✕" : label}
      </span>
    </div>
  );
}
