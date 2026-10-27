import { makeStyles } from '@material-ui/core';
import { MarkBars } from './MarkBars';

const useStyles = makeStyles({
  svg: {
    width: 'auto',
    height: 28,
  },
});

/** The colour mark alone, for the closed sidebar. */
export const LogoIcon = () => {
  const classes = useStyles();

  return (
    <svg
      className={classes.svg}
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 40 40"
    >
      <MarkBars />
    </svg>
  );
};
