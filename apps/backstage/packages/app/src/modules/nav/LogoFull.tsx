import { makeStyles } from '@material-ui/core';
import { MarkBars } from './MarkBars';

// Rendered 24 px high the logo is about 136 px wide, above the 120 px minimum, and
// the sidebar's 24 px inset gives it clear space of one mark height.
const useStyles = makeStyles({
  svg: {
    width: 'auto',
    height: 24,
  },
});

/**
 * The colour mark with the wordmark ManyStage in Inter SemiBold. The sidebar is dark
 * in both themes, so the wordmark takes the light ink of a dark ground.
 */
export const LogoFull = () => {
  const classes = useStyles();

  return (
    <svg
      className={classes.svg}
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 226 40"
    >
      <MarkBars />
      <text
        x="52"
        y="31"
        fontFamily="Inter, -apple-system, BlinkMacSystemFont, sans-serif"
        fontSize="32"
        fontWeight="600"
        letterSpacing="-0.6"
        fill="#F1F5F9"
      >
        ManyStage
      </text>
    </svg>
  );
};
