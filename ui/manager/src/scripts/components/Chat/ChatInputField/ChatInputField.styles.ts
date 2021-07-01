import { makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  GREEN_COLOR,
  LIGHT_BLUE_COLOR2,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatInputContainer: {
    alignSelf: 'flex-end',
  },
  chatInput: {
    display: 'flex',

    '& .MuiOutlinedInput-root': {
      borderRadius: '15px',
    },
    '& .MuiInputBase-input': {
      fontSize: '1.3rem',
      zIndex: 1,
    },
    '& .MuiOutlinedInput-notchedOutline': {
      backgroundColor: WHITE_COLOR,
      border: 'none',
    },
    '& .MuiOutlinedInput-root.Mui-focused .MuiOutlinedInput-notchedOutline': {
      border: 'none',
    },
  },
  row: {
    display: 'flex',
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    marginBottom: '20px',
    width: '100%',
    position: 'relative',
  },
});

export default useStyles;
