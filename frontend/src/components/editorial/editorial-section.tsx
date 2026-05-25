/**
 * Editorial layout primitive for the About page (leaf 4f).
 *
 * 720-px max width (decision [104] editorial-text measure), Source Serif 4
 * headline at order 2 (scale[6] = 48 px via the Mantine theme's headings.sizes),
 * generous vertical rhythm. Pure presentation — sections compose this with
 * their own prose.
 */
import { Box, Stack, Title } from "@mantine/core";
import type { ReactNode } from "react";

import { layouts } from "../../design/tokens";

export type EditorialSectionProps = {
  title: string;
  children: ReactNode;
  /** Optional eyebrow label rendered above the title (e.g., "01 — Overview"). */
  eyebrow?: string;
};

export function EditorialSection({
  title,
  children,
  eyebrow,
}: EditorialSectionProps) {
  return (
    <Box
      component="section"
      style={{
        maxWidth: layouts.editorialMaxWidth,
        marginLeft: "auto",
        marginRight: "auto",
        paddingTop: 64,
        paddingBottom: 64,
      }}
    >
      <Stack gap="md">
        {eyebrow ? (
          <Title
            order={6}
            tt="uppercase"
            c="dimmed"
            style={{ margin: 0, letterSpacing: "0.08em" }}
          >
            {eyebrow}
          </Title>
        ) : null}
        <Title order={2} style={{ margin: 0 }}>
          {title}
        </Title>
        <Stack gap="md">{children}</Stack>
      </Stack>
    </Box>
  );
}
