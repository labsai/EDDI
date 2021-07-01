import { makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  DARK_GREY_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  quickReplies: {
    display: 'flex',
    flexDirection: 'row',
    flexWrap: 'wrap',
    margin: '10px 0',
  },
  quickRepliesButton: {
    marginRight: '10px',
    marginBottom: '5px',
    borderRadius: '15px',
    backgroundColor: BLUE_COLOR,
    color: WHITE_COLOR,
    transition: 'none',
    fontSize: '1rem',
    padding: '4px 10px',

    '&:hover': {
      backgroundColor: 'transparent',
      border: `2px solid ${BLUE_COLOR}`,
      color: BLUE_COLOR,
      padding: '2px 8px',
      transition: 'none',
    },
  },
  hidden: {
    display: 'none',
  },
});

export default useStyles;
