import { makeStyles } from '@material-ui/core/styles';
import {
  LIGHT_BLUE_COLOR3,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  ticontainer: {
    backgroundColor: LIGHT_BLUE_COLOR3,
    borderRadius: '10px',
    padding: '5px',
    marginBottom: '10px',
  },
  tiblock: {
    alignItems: 'center',
    display: 'flex',
    height: '17px',
  },
  tidot: {
    animation: '$mercuryTypingAnimation 1.5s infinite ease-in-out',
    borderRadius: '2px',
    display: 'inline-block',
    height: '4px',
    marginRight: '2px',
    width: '4px',
    backgroundColor: WHITE_COLOR,

    '&:nth-child(1)': {
      animationDelay: '200ms',
    },
    '&:nth-child(2)': {
      animationDelay: '300ms',
    },
    '&:nth-child(3)': {
      animationDelay: '400ms',
    },
  },
  '@keyframes mercuryTypingAnimation': {
    '0%': {
      transform: 'translateY(0px)',
    },
    '28%': {
      transform: 'translateY(-5px)',
    },
    '44%': {
      transform: 'translateY(0px)',
    },
  },
});

export default useStyles;
