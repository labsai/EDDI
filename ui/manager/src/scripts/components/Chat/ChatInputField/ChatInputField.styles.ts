import { makeStyles } from '@material-ui/core/styles';
import {
  GREEN_COLOR,
  LIGHT_BLUE_COLOR2,
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
      backgroundColor: LIGHT_BLUE_COLOR2,
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
