/** Longest requested path the 404 echoes back; anything past it is clipped with "...". */
export const PATH_ECHO_MAX = 120;

/** The requested path as the 404's scorebook prints it (React escapes it; this only bounds it). */
export function echoPath(pathname: string): string {
  return pathname.length > PATH_ECHO_MAX
    ? `${pathname.slice(0, PATH_ECHO_MAX)}...`
    : pathname;
}
