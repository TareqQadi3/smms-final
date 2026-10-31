import { AlertCircle, Inbox } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

/**
 * Minimal empty / error message for lists. Errors use role="alert" and offer a retry.
 */
function StateMessage({
  kind = "empty",
  children,
  onRetry,
  retryLabel,
  className,
}: {
  kind?: "empty" | "error"
  children: React.ReactNode
  onRetry?: () => void
  retryLabel?: string
  className?: string
}) {
  const Icon = kind === "error" ? AlertCircle : Inbox
  return (
    <div
      role={kind === "error" ? "alert" : "status"}
      className={cn(
        "flex flex-col items-center justify-center gap-2 px-4 py-10 text-center text-sm",
        kind === "error" ? "text-destructive" : "text-muted-foreground",
        className
      )}
    >
      <Icon size={22} aria-hidden="true" />
      <p>{children}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          {retryLabel}
        </Button>
      )}
    </div>
  )
}

export { StateMessage }
