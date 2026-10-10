"use client";

import { useState } from "react";
import { XAN_LINKS } from "@/lib/links";
import { useLanguage } from "@/lib/i18n";

const platforms = [
  {
    id: "windows",
    label: "Windows",
    shell: "PowerShell",
    command: `git clone ${XAN_LINKS.clone}; Set-Location xan`,
  },
  {
    id: "macos",
    label: "macOS",
    shell: "Terminal",
    command: `git clone ${XAN_LINKS.clone} && cd xan`,
  },
  {
    id: "linux",
    label: "Linux",
    shell: "Terminal",
    command: `git clone ${XAN_LINKS.clone} && cd xan`,
  },
] as const;

async function copyCommand(command: string) {
  try {
    await navigator.clipboard.writeText(command);
    return true;
  } catch {
    const field = document.createElement("textarea");
    field.value = command;
    field.setAttribute("readonly", "");
    field.style.position = "fixed";
    field.style.opacity = "0";
    document.body.appendChild(field);
    field.select();
    const copied = document.execCommand("copy");
    field.remove();
    return copied;
  }
}

export function CloneCommand() {
  const { t } = useLanguage();
  const [selected, setSelected] = useState<(typeof platforms)[number]["id"]>("windows");
  const [status, setStatus] = useState<"idle" | "copied" | "failed">("idle");
  const platform = platforms.find((item) => item.id === selected) ?? platforms[0];

  const selectPlatform = (id: typeof selected) => {
    setSelected(id);
    setStatus("idle");
  };

  const copy = async () => {
    setStatus((await copyCommand(platform.command)) ? "copied" : "failed");
    window.setTimeout(() => setStatus("idle"), 1800);
  };

  return (
    <div className="clone-command-wrap">
      <div className="clone-platforms" aria-label={t("Choose your operating system")}>
        {platforms.map((item) => (
          <button
            key={item.id}
            type="button"
            aria-pressed={selected === item.id}
            onClick={() => selectPlatform(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div className="clone-shell-label">{platform.shell === "Terminal" ? t("Terminal") : platform.shell}</div>
      <div className="clone-command">
        <code title={platform.command}>{platform.command}</code>
        <button type="button" onClick={copy} aria-label={`${t("Copy")} ${platform.label} ${t("clone command")} `}>
          {status === "copied" ? t("Copied") : status === "failed" ? t("Select") : t("Copy")}
        </button>
      </div>
      <p className="clone-copy-status" role="status" aria-live="polite">
        {status === "copied" ? `${platform.label} ${t("command copied.")}` : status === "failed" ? t("Copy failed. Select the command above.") : ""}
      </p>
    </div>
  );
}
