import { makeStyles } from '@material-ui/core/styles';
import {
  GREY_COLOR,
  LIGHT_BLUE_COLOR2,
  LIGHT_BLUE_COLOR3,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatOutput: {
    display: 'flex',
    margin: '0 0 10px',
  },
  outputText: {
    color: WHITE_COLOR,
    padding: '5px 10px',
    margin: 0,
    backgroundColor: LIGHT_BLUE_COLOR3,
    borderRadius: '15px',
    overflow: 'hidden',
  },
  chatInput: {
    display: 'flex',
    alignSelf: 'flex-end',
    marginBottom: '20px',
    marginTop: '10px',
  },
  inputText: {
    backgroundColor: YELLOW_COLOR,
  },
});

export default useStyles;
