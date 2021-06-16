import { makeStyles } from '@material-ui/core/styles';
import { LIGHT_BLUE_COLOR2 } from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatInputContainer: {
    alignSelf: 'flex-end',
  },
  chatInput: {
    display: 'flex',
    alignSelf: 'flex-end',
    marginBottom: '20px',

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
});

export default useStyles;
