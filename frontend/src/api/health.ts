export async function isBackendHealthy(): Promise<boolean> {
  try {
    const response = await fetch("/actuator/health");
    if (!response.ok) {
      return false;
    }
    const body: { status?: string } = await response.json();
    return body.status === "UP";
  } catch {
    return false;
  }
}
