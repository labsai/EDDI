import { CSSProperties } from 'react';
import {
  BLUE_COLOR2,
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LARGE_FONT3,
  LIGHT_BLUE_COLOR2,
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR2,
  LIGHT_GREY_COLOR3,
  MEDIUM_FONT2,
  RED_COLOR,
  SMALL_FONT2,
} from '../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  content: {
    paddingBottom: '500px',
  },
  tabs: {
    display: 'flex',
    marginTop: '30px',
  },
  tab: {
    ':hover': {
      backgroundColor: LIGHT_GREY_COLOR3,
      borderBottom: `3px solid ${GREY_COLOR}`,
    },
    borderBottom: `3px solid ${BLUE_COLOR2}`,
    color: DARK_BLUE_COLOR,
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: SMALL_FONT2,
    height: '39px',
    textAlign: 'center',
    flex: '1',
    lineHeight: '42px',
  },
  tabDisabled: {
    borderBottom: `3px solid ${LIGHT_GREY_COLOR2}`,
    color: GREY_COLOR,
  },
  header: {
    marginTop: '45px',
  },
  bottomHeader: {
    color: DARK_BLUE_COLOR,
    fontSize: SMALL_FONT2,
    display: 'flex',
    marginTop: '40px',
    overflow: 'hidden',
    width: '100%',
  },
  descriptor: {
    marginRight: '65px',
  },
  topHeader: {
    display: 'flex',
  },
  botName: {
    ':hover': {
      backgroundColor: LIGHT_BLUE_COLOR2,
    },
    color: DARK_BLUE_COLOR,
    fontSize: LARGE_FONT3,
    textAlign: 'left',
    maxWidth: '350px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    cursor: 'pointer',
  },
  botVersion: {
    backgroundColor: LIGHT_GREY_COLOR2,
    borderRadius: '100px',
    color: DARK_BLUE_COLOR,
    fontSize: MEDIUM_FONT2,
    height: '22px',
    marginLeft: '15px',
    padding: '2px 12px',
    textAlign: 'center',
    width: 'fit-content',
    lineHeight: '19px',
    marginTop: '9px',
  },
  endConversationButton: {
    color: RED_COLOR,
    border: `1px solid ${RED_COLOR}`,
    width: '150px',
    marginLeft: 'auto',
    disabled: {
      border: LIGHT_GREY_BORDER,
    },
  },
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
};
export default styles;
