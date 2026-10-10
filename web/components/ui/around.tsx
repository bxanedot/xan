type AroundProps = {
  toggled: boolean;
  onClick: () => void;
};

export function Around({ toggled, onClick }: AroundProps) {
  return (
    <button
      type="button"
      className="around-toggle"
      onClick={onClick}
      aria-pressed={toggled}
      aria-label={toggled ? "Switch to black and white mode" : "Switch to black and gold mode"}
      title={toggled ? "Black and gold" : "Black and white"}
    >
      <span className="around-orbit" aria-hidden="true"><span className="around-orbit-point" /></span>
      <span className="sr-only">{toggled ? "Gold theme active" : "Black and white theme active"}</span>
    </button>
  );
}
