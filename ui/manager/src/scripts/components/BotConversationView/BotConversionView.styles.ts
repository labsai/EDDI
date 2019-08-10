import { CSSProperties } from 'react';
import {
  BLUE_COLOR,
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LARGE_FONT3,
  LIGHT_BLUE_COLOR2,
  MEDIUM_FONT,
  RED_COLOR,
  SMALL_FONT,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  content: {
    position: 'absolute',
    display: 'flex',
    overflow: 'hidden',
    left: '100px',
    right: '100px',
    top: '20px',
    bottom: '20px',
  },
  chat: {
    fontSize: MEDIUM_FONT,
    flex: '1',
    marginRight: '5px',
  },
  data: {
    color: '#7A849E',
    fontSize: '14px',
    backgroundColor: '#FFF',
    flex: '1',
    overflow: 'auto',
    whiteSpace: 'pre-wrap',
    marginLeft: '5px',
    marginTop: '10px',
    marginBottom: '20px',
  },
  input: {
    backgroundColor: DARK_BLUE_COLOR,
    borderRadius: '18px',
    color: WHITE_COLOR,
    width: 'fit-content',
    padding: '14px 28px',
    marginLeft: 'auto',
    marginTop: '12px',
  },
  output: {
    backgroundColor: LIGHT_BLUE_COLOR2,
    borderRadius: '18px',
    width: 'fit-content',
    padding: '14px 28px',
    marginTop: '12px',
  },
  quickSelect: {
    marginLeft: '5px',
    backgroundColor: GREY_COLOR,
    borderRadius: '18px',
    color: WHITE_COLOR,
    width: 'fit-content',
    padding: '14px 28px',
  },
  quickSelected: {
    marginLeft: '5px',
    backgroundColor: DARK_BLUE_COLOR,
    borderRadius: '18px',
    color: WHITE_COLOR,
    width: 'fit-content',
    padding: '14px 28px',
  },
  quickSelectGroup: {
    marginTop: '12px',
    width: 'fit-content',
    display: 'flex',
    marginLeft: 'auto',
  },
};
export default styles;
