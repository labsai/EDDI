import {
  WHITE_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR2,
  SMALL_FONT2,
  DARK_GREY_COLOR,
  BLUE_COLOR,
  BLACK_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { makeStyles } from '@material-ui/core/styles';

const editStyles = makeStyles({
  tabs: {
    display: 'flex',
  },
  tab: {
    backgroundColor: DARK_GREY_COLOR,
    '&:hover': {
      color: BLUE_COLOR,
      borderBottom: `3px solid ${BLUE_COLOR}`,
    },
    borderBottom: `3px solid ${BLUE_COLOR}`,
    color: WHITE_COLOR,
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: SMALL_FONT2,
    height: '39px',
    textAlign: 'center',
    flex: 1,
    lineHeight: '42px',
  },
  tabDisabled: {
    borderBottom: `3px solid ${LIGHT_GREY_COLOR2}`,
    color: GREY_COLOR,
  },
  schemaForm: {
    paddingTop: '20px',
  },
});

export default editStyles;
